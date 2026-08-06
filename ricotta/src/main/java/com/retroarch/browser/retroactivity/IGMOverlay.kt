package com.retroarch.browser.retroactivity

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
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

private const val IGM_OVERLAY_THEME = android.R.style.Theme_Translucent_NoTitleBar_Fullscreen

// applyOverrideConfiguration has to land before anything reads resources off the wrapper, so the
// context is built once up front rather than adjusted later.
private fun localeContext(activity: Activity, tag: String): Context {
    if (tag.isEmpty()) return activity
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return activity
    return ContextThemeWrapper(activity, IGM_OVERLAY_THEME).apply {
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
    // overlay window still resolves a token from it.
    private val uiContext: Context = localeContext(activity, localeTag)

    val controller = IGMController(bridge, gameTitle)
    private var composeView: ComposeView? = null
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
            isFocusable = true
            isFocusableInTouchMode = true
            // The platform draws a focus highlight around a focused view that does not define its
            // own, which showed up as a border around the whole menu once this view started taking
            // focus. The menu draws its own selection, so suppress it.
            defaultFocusHighlightEnabled = false

            setContent {
                IGMContent()
            }

            // Same contract the Dialog's listener had: consume everything so nothing reaches the
            // game, and ignore the menu key briefly after opening so the press that opened the
            // menu does not immediately close it.
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val isMenuKey = keyCode == KeyEvent.KEYCODE_BACK
                            || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                            || keyCode == KeyEvent.KEYCODE_MENU
                    if (isMenuKey && System.currentTimeMillis() - showTimeMs < 500) {
                        // Ignore, this is the same press that opened the menu
                    } else {
                        controller.handleKeyDown(keyCode)
                    }
                }
                true
            }
        }
        composeView = view

        // A panel window rather than a Dialog, added once and never removed.
        //
        // A Dialog detaches its content view on dismiss, which disposes the composition, so every
        // open rebuilt the whole menu tree. Retaining the composition instead is not a fix either:
        // the recomposer belongs to the window, so a retained composition on a dismissed Dialog
        // stops recomposing and the selection highlight freezes. Keeping one window attached for
        // the session avoids both, and OsdOverlay already draws over the same GL surface this way.
        //
        // Deferred until the activity window is attached so the token is available.
        activity.window.decorView.post {
            runCatching { activity.windowManager.addView(view, panelParams(focusable = false)) }
            // Composition already happens here: AbstractComposeView creates it in
            // onAttachedToWindow, not at layout, so visibility does not defer it. What the first
            // open still pays for is the first draw, rasterizing glyphs for the custom font and
            // allocating the window's surface, and a view that is never drawn cannot pay that
            // early. GONE rather than INVISIBLE so there is no layout pass during gameplay.
            view.visibility = View.GONE
        }
    }

    /**
     * Focusable only while the menu is up. RetroArch's native input hook consumes gamepad input
     * whenever the IGM is visible and does not forward it, so the menu has to hold window focus to
     * receive keys, exactly as the Dialog did. Hidden, the window is neither focusable nor
     * touchable and the game is unaffected.
     */
    private fun panelParams(focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (focusable) 0
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        token = activity.window.decorView.windowToken
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        composeView?.let { runCatching { activity.windowManager.removeView(it) } }
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
        composeView?.let { v ->
            v.visibility = View.VISIBLE
            runCatching { activity.windowManager.updateViewLayout(v, panelParams(focusable = true)) }
            v.requestFocus()
            // Immersive state belongs to whichever window holds focus, so taking focus brings the
            // system bars back over the game: a strip along the bottom, and the menu shifting as
            // the insets change. This window has to ask for immersive mode on its own behalf, the
            // same thing the Dialog used to do through its Window.
            ViewCompat.getWindowInsetsController(v)?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun hide() {
        if (!showing) return
        showing = false
        bridge.setIGMVisible(false)
        composeView?.let { v ->
            // Flags first, then hide: the window stops taking input before it stops drawing, so
            // no key can land on a menu that is on its way out.
            runCatching { activity.windowManager.updateViewLayout(v, panelParams(focusable = false)) }
            v.visibility = View.GONE
        }
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
