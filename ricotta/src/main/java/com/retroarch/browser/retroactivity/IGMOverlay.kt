package com.retroarch.browser.retroactivity

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.cannoli.igm.CannoliIGM
import dev.cannoli.igm.GuideManager
import dev.cannoli.igm.IGMController
import dev.cannoli.igm.IGMHostConfig
import dev.cannoli.igm.IgmGameInfo
import dev.cannoli.igm.RaOptionStrings
import dev.cannoli.ui.R
import com.retroarch.R as AppR
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.hexToColor
import android.graphics.Typeface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dev.cannoli.ricotta.RicottaArchBridge
import java.util.Locale

private const val IGM_DIALOG_THEME = android.R.style.Theme_Translucent_NoTitleBar_Fullscreen

// applyOverrideConfiguration has to land before anything reads resources off the wrapper, so the
// context is built once up front rather than adjusted later.
private fun localeContext(activity: Activity, tag: String): Context {
    if (tag.isEmpty()) return activity
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return activity
    return ContextThemeWrapper(activity, IGM_DIALOG_THEME).apply {
        applyOverrideConfiguration(Configuration().apply { setLocales(LocaleList(locale)) })
    }
}

/**
 * Custom lifecycle owner for hosting Compose inside NativeActivity,
 * which does not implement LifecycleOwner or SavedStateRegistryOwner.
 */
internal class IGMLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun performCreate(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun performStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun performResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun performPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun performStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun performDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * Manages a Compose overlay on top of a NativeActivity for the In-Game Menu (IGM).
 *
 * Since NativeActivity doesn't provide the lifecycle interfaces that Compose requires,
 * this class manually sets up a LifecycleOwner and SavedStateRegistryOwner and attaches
 * them to the ComposeView's view tree.
 */
class IGMOverlay(
    private val activity: Activity,
    private val bridge: RicottaArchBridge,
    gameTitle: String,
    private val hostConfig: IGMHostConfig,
    private val cannoliRoot: String = "",
    private val platformTag: String = "",
    private val platformName: String = "",
    private val colorHighlight: String? = null,
    private val colorText: String? = null,
    private val colorHighlightText: String? = null,
    private val colorAccent: String? = null,
    private val colorTitle: String? = null,
    localeTag: String = ""
) {
    // The launcher's language choice, applied to the IGM only. Wrapping the activity itself would
    // re-localize RetroArch's own resources; ContextThemeWrapper keeps the activity as base so the
    // Dialog still resolves a window token.
    private val uiContext: Context = localeContext(activity, localeTag)

    val controller = IGMController(bridge, gameTitle)
    private var composeView: ComposeView? = null
    private var dialog: Dialog? = null
    private val lifecycleOwner = IGMLifecycleOwner()
    private var showTimeMs = 0L
    private var fontFamily: FontFamily = FontFamily.Default

    fun onCreate(savedInstanceState: Bundle?) {
        fontFamily = runCatching {
            val tf = Typeface.createFromAsset(activity.assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
            FontFamily(ComposeTypeface(tf))
        }.getOrDefault(FontFamily.Default)
        lifecycleOwner.performCreate(savedInstanceState)

        // Wire up the Cannoli IGM trigger from the C input handler
        bridge.onIgmTrigger = { show() }

        // Forward gamepad input to IGM controller when visible
        bridge.onDebugKey = { keycode ->
            controller.handleKeyDown(keycode)
        }

        // Wire up controller callbacks
        controller.onClose = { hide() }
        controller.onOpenNativeMenu = {
            hide()
            bridge.openNativeMenu()
        }
        bridge.onOpenNativeMenu = controller.onOpenNativeMenu

        bridge.raStrings = RaOptionStrings(
            rootTitle = uiContext.getString(R.string.igm_settings),
            on = uiContext.getString(AppR.string.igm_ra_on),
            off = uiContext.getString(AppR.string.igm_ra_off),
            restartHint = uiContext.getString(AppR.string.igm_ra_restart_hint),
            savePlatform = uiContext.getString(AppR.string.igm_ra_save_platform, platformName),
            saveGame = uiContext.getString(AppR.string.igm_ra_save_game),
            dontSave = uiContext.getString(AppR.string.igm_ra_dont_save),
            nativeMenu = uiContext.getString(AppR.string.igm_ra_native_menu),
            categoryTitles = mapOf(
                "video" to uiContext.getString(R.string.igm_video),
                "audio" to uiContext.getString(AppR.string.igm_ra_audio),
                "latency" to uiContext.getString(AppR.string.igm_ra_latency),
                "speed" to uiContext.getString(AppR.string.igm_ra_speed),
                "osd" to uiContext.getString(AppR.string.igm_ra_osd),
            ),
        )

        // Discover guides for this game so the IGM can show them.
        if (cannoliRoot.isNotEmpty()) {
            controller.attachGuides(GuideManager(cannoliRoot, platformTag, controller.gameTitle))
        }

        val view = ComposeView(uiContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                IGMContent()
            }
        }
        composeView = view

        // Use a full-screen Dialog to render on top of NativeActivity's GL surface
        dialog = Dialog(uiContext, IGM_DIALOG_THEME).apply {
            setContentView(view)
            window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // Lay the Dialog content out edge-to-edge from creation, the same way the
                // built-in runner's activity does (WindowCompat, not the deprecated
                // systemUiVisibility flags), so the content bounds are settled on the first
                // frame instead of shifting as the immersive transition lands.
                WindowCompat.setDecorFitsSystemWindows(this, false)
            }
            setCancelable(false)
            // Handle gamepad input through the Dialog when IGM is visible,
            // since NativeActivity's AInputQueue loses focus when a Dialog is showing
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Debounce BACK/menu keys for 500ms after opening to prevent
                    // the same button press that opened the menu from closing it
                    val isMenuKey = keyCode == KeyEvent.KEYCODE_BACK
                            || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                            || keyCode == KeyEvent.KEYCODE_MENU
                    if (isMenuKey && System.currentTimeMillis() - showTimeMs < 500) {
                        // Ignore — this is the same press that opened the menu
                    } else {
                        controller.handleKeyDown(keyCode)
                    }
                }
                true // consume all events to prevent Dialog dismissal
            }
        }
    }

    fun onResume() {
        lifecycleOwner.performStart()
        lifecycleOwner.performResume()
    }

    fun onPause() {
        lifecycleOwner.performPause()
        lifecycleOwner.performStop()
    }

    fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        lifecycleOwner.performDestroy()
        composeView = null
    }

    private var showing = false

    fun show() {
        if (showing) return
        showing = true
        showTimeMs = System.currentTimeMillis()
        controller.openMenu()
        bridge.pause()
        bridge.setIGMVisible(true)
        dialog?.let { d ->
            val w = d.window
            // Show the Dialog non-focusable first so gaining focus does not disturb the
            // activity's immersive state, then drive the Dialog's own window into the same
            // edge-to-edge immersive mode the built-in runner uses. Restore focus for input.
            w?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            d.show()
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, false)
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            w?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    fun hide() {
        if (!showing) return
        showing = false
        bridge.setIGMVisible(false)
        dialog?.dismiss()
        controller.closeMenu()
        bridge.unpause()
    }

    fun isVisible(): Boolean = showing

    @Composable
    private fun IGMContent() {
        val colors = CannoliColors(
            highlight = colorHighlight?.let { hexToColor(it) } ?: Color.White,
            text = colorText?.let { hexToColor(it) } ?: Color.White,
            highlightText = colorHighlightText?.let { hexToColor(it) } ?: Color.Black,
            accent = colorAccent?.let { hexToColor(it) } ?: Color.White,
            title = colorTitle?.let { hexToColor(it) } ?: Color.White
        )
        CannoliTheme(fontFamily = fontFamily) {
            CompositionLocalProvider(LocalCannoliColors provides colors) {
                CannoliIGM(
                    screen = controller.currentScreen,
                    config = hostConfig,
                    gameTitle = controller.gameTitle,
                    menuOptions = controller.buildMenuOptions(),
                    selectedSlot = controller.currentSlot,
                    slotThumbnail = controller.slotThumbnail.value,
                    slotExists = controller.slotExists.value,
                    slotOccupied = controller.slotOccupied.value,
                    undoLabel = controller.undoLabel.value,
                    settingsItems = controller.settingsItems.value,
                    coreInfo = "",
                    gameInfo = IgmGameInfo(),
                    infoScrollDir = 0,
                    guideFiles = controller.guideFiles.value,
                    guidePageCount = controller.guidePageCount.intValue,
                    guideScrollDir = controller.guideScrollDir.intValue,
                    guideScrollXDir = controller.guideScrollXDir.intValue,
                    guidePageJump = controller.guidePageJump.intValue,
                    guidePageJumpDir = controller.guidePageJumpDir.intValue,
                    guideInitialScroll = controller.guideInitialScroll.intValue,
                    guideInitialScrollX = controller.guideInitialScrollX.intValue,
                    onGuideScrollChanged = controller::onGuideScrollChanged,
                )
            }
        }
    }
}
