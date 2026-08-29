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
import android.view.ViewTreeObserver
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
import dev.cannoli.igm.CheatManager
import dev.cannoli.igm.GuideController
import dev.cannoli.igm.GuideDisplays
import dev.cannoli.igm.GuideFile
import dev.cannoli.igm.GuideManager
import dev.cannoli.igm.GuideOverlayContract
import dev.cannoli.igm.IGMController
import dev.cannoli.igm.IGMHostConfig
import dev.cannoli.igm.CuratedCatalog
import dev.cannoli.core.overlay.OverlayCatalog
import java.io.File
import dev.cannoli.igm.RaOptionStrings
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.cannoliColorsFromHex
import android.graphics.Typeface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dev.cannoli.core.SaveSlotStore
import dev.cannoli.ricotta.EmbeddedRetroArchBridge
import java.util.Locale

private const val IGM_OVERLAY_THEME = android.R.style.Theme_Translucent_NoTitleBar_Fullscreen

// applyOverrideConfiguration has to land before anything reads resources off the wrapper, so the
// context is built once up front rather than adjusted later.
internal fun localeContext(activity: Activity, tag: String): Context {
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
    private val bridge: EmbeddedRetroArchBridge,
    stateBasePath: String,
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
    localeTag: String = "",
    private val romBaseName: String = "",
) {
    // The launcher's language choice, applied to the IGM only. Wrapping the activity itself would
    // re-localize RetroArch's own resources; ContextThemeWrapper keeps the activity as base so the
    // overlay window still resolves a token from it.
    private val uiContext: Context = localeContext(activity, localeTag)

    val controller = IGMController(bridge, gameTitle, SaveSlotStore(stateBasePath))

    /** Set by the host so the IGM can put a line on the shared OSD. */
    var onOsdMessage: ((String) -> Unit)? = null

    /** Fired once the menu's window is up, so the host can keep the OSD above it. */
    var onWindowAttached: (() -> Unit)? = null

    /** Fired once the whole menu has closed and play has resumed. */
    var onHidden: (() -> Unit)? = null

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
            suspendedForNativeMenu = true
            hideWindow()
            bridge.unpause()
            controller.suspendForNativeMenu()
        }
        controller.onNativeMenuClosed = {
            if (suspendedForNativeMenu) {
                suspendedForNativeMenu = false
                controller.invalidateSlotCache()
                controller.refreshSlotInfo()
                showWindow()
            }
        }
        bridge.onOpenNativeMenu = controller.onOpenNativeMenu

        bridge.raStrings = RaOptionStrings(
            rootTitle = uiContext.getString(R.string.igm_settings),
            on = uiContext.getString(R.string.igm_on),
            off = uiContext.getString(R.string.igm_off),
            restartHint = uiContext.getString(R.string.igm_restart_hint),
            savePlatform = uiContext.getString(R.string.igm_save_platform, platformName),
            saveGame = uiContext.getString(R.string.igm_save_game),
            dontSave = uiContext.getString(R.string.igm_dont_save),
            emulator = uiContext.getString(R.string.igm_emulator),
            shaderApplied = uiContext.getString(R.string.igm_shader_applied),
            shaderLoad = uiContext.getString(R.string.igm_shader_load),
            shaderAddStart = uiContext.getString(R.string.igm_shader_add_start),
            shaderAddEnd = uiContext.getString(R.string.igm_shader_add_end),
            shaderSave = uiContext.getString(R.string.igm_shader_save),
            shaderPass = { uiContext.getString(R.string.igm_shader_pass, it) },
            shaderPassNamed = { i, name -> uiContext.getString(R.string.igm_shader_pass_named, i, name) },
            shaderParameters = uiContext.getString(R.string.igm_shader_parameters),
            shaderPreset = uiContext.getString(R.string.igm_shader_preset),
            shaderFilter = uiContext.getString(R.string.igm_shader_filter),
            shaderScale = uiContext.getString(R.string.igm_shader_scale),
            shaderUnspecified = uiContext.getString(R.string.igm_shader_unspecified),
            shaderFilterLinear = uiContext.getString(R.string.igm_shader_filter_linear),
            shaderFilterNearest = uiContext.getString(R.string.igm_shader_filter_nearest),
            shaderScaleX = { uiContext.getString(R.string.igm_shader_scale_x, it) },
            shaderNone = uiContext.getString(R.string.igm_shader_none),
            custom = uiContext.getString(R.string.igm_curated_custom),
            infoCore = uiContext.getString(R.string.igm_info_core),
            infoCoreVersion = uiContext.getString(R.string.igm_info_core_version),
            curatedCategoryTitles = mapOf(
                CuratedCatalog.CATEGORY_VIDEO to uiContext.getString(R.string.igm_video),
                CuratedCatalog.CATEGORY_EMULATOR to uiContext.getString(R.string.igm_emulator),
                CuratedCatalog.CATEGORY_ADVANCED to uiContext.getString(R.string.igm_advanced),
                CuratedCatalog.CATEGORY_INFO to uiContext.getString(R.string.igm_info),
                CuratedCatalog.CATEGORY_OVERLAY to uiContext.getString(R.string.igm_overlay),
                CuratedCatalog.CATEGORY_SHADER to uiContext.getString(R.string.igm_shader),
            ),
            curatedRowLabels = mapOf(
                "curated_screen_scaling" to uiContext.getString(R.string.igm_curated_screen_scaling),
                "curated_screen_sharpness" to uiContext.getString(R.string.igm_curated_screen_sharpness),
                "curated_max_ff_speed" to uiContext.getString(R.string.igm_curated_max_ff_speed),
                "curated_show_fps" to uiContext.getString(R.string.igm_curated_show_fps),
                "curated_debug_hud" to uiContext.getString(R.string.igm_curated_debug_hud),
            ),
            curatedPresetLabels = mapOf(
                "scaling_core_reported" to uiContext.getString(R.string.igm_scaling_core_reported),
                "scaling_integer" to uiContext.getString(R.string.igm_scaling_integer),
                "scaling_fullscreen" to uiContext.getString(R.string.igm_scaling_fullscreen),
                "sharpness_sharp" to uiContext.getString(R.string.igm_sharpness_sharp),
                "sharpness_soft" to uiContext.getString(R.string.igm_sharpness_soft),
                "ff_2x" to uiContext.getString(R.string.igm_ff_multiplier, 2),
                "ff_4x" to uiContext.getString(R.string.igm_ff_multiplier, 4),
                "ff_8x" to uiContext.getString(R.string.igm_ff_multiplier, 8),
                "ff_unlimited" to uiContext.getString(R.string.igm_ff_unlimited),
                "on" to uiContext.getString(R.string.igm_on),
                "off" to uiContext.getString(R.string.igm_off),
            ),
        )

        // Discover guides and cheats for this game so the IGM can show them.
        if (cannoliRoot.isNotEmpty()) {
            controller.attachGuides(GuideManager(cannoliRoot, platformTag, romBaseName))
            controller.attachCheats(CheatManager(cannoliRoot, platformTag, romBaseName))
        }
        attachOverlayPicker()
        controller.openGuideExternally = ::showGuideOnSecondDisplay
        controller.onCheatsRestored = { count ->
            onOsdMessage?.invoke(
                uiContext.resources.getQuantityString(R.plurals.osd_cheats_restored, count, count)
            )
        }

        // Read the slots while the game is still loading so the first open of the menu has a
        // thumbnail and its dots already, rather than filling them in once the user is looking.
        controller.refreshSlotInfo()

        val view = ComposeView(uiContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false

            setContent {
                IGMContent()
            }

            setOnKeyListener { _, keyCode, event ->
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (controller.isMenuKey(keyCode) && System.currentTimeMillis() - showTimeMs < 500) {
                            // Same press that opened the menu.
                        } else {
                            controller.handleKeyDown(keyCode)
                        }
                    }
                    // The guide scrolls for as long as a direction is held, so the release has to
                    // arrive or it never stops.
                    KeyEvent.ACTION_UP -> controller.handleKeyUp(keyCode)
                }
                true
            }
        }
        composeView = view
        // A stored bezel has to reach the screen without anyone opening the menu, but a panel window
        // cannot be added yet: panelParams needs the activity's window token, and during onCreate the
        // decor view has none, so addView throws and the attach is silently lost. Posting to the
        // decor view runs this once the host window is attached and the token exists.
        activity.window.decorView.post { showOverlayLayer() }
    }

    // The overlay is a started service in the launcher's process, so the guide stays on the panel
    // once the menu closes and play resumes. Any reason it cannot reach the panel returns false and
    // the IGM shows the guide itself rather than leaving the user with nothing.
    private fun showGuideOnSecondDisplay(guide: GuideFile, open: GuideController.GuideOpenState): Boolean {
        val displayId = GuideDisplays.secondDisplayId(activity) ?: return false
        if (!GuideOverlayContract.canShow(activity)) return false
        return GuideOverlayContract.start(
            context = activity,
            displayId = displayId,
            filePath = open.filePath,
            guideType = guide.type,
            platformTag = platformTag,
            romBaseName = romBaseName,
            page = open.initialPage,
            scrollY = controller.guideInitialScroll.intValue,
            scrollX = controller.guideInitialScrollX.intValue,
            textZoom = open.textZoom,
            pageCount = controller.guidePageCount.intValue,
        )
    }

    private var attached = false

    // A bezel attaches this window before any menu exists, and it must not take input from the
    // game to do it. showWindow raises focus when the menu actually opens.
    private fun attachIfNeeded(view: ComposeView, focusable: Boolean = true): Boolean {
        if (attached) return true
        attached = runCatching {
            activity.windowManager.addView(view, panelParams(focusable = focusable))
        }.isSuccess
        // Sibling panel windows stack in the order they were added, so the OSD, added first, is
        // under this one until the host puts it back on top.
        if (attached) onWindowAttached?.invoke()
        return attached
    }

    private fun panelParams(focusable: Boolean, alpha: Float = 1f) = WindowManager.LayoutParams(
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
        this.alpha = alpha
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
        if (attached) composeView?.let { runCatching { activity.windowManager.removeView(it) } }
        attached = false
        lifecycleOwner.performDestroy()
        composeView = null
    }

    private var showing = false
    private var suspendedForNativeMenu = false

    fun show() {
        if (showing) return
        suspendedForNativeMenu = false
        controller.openMenu()
        showWindow()
    }

    private fun showWindow() {
        showing = true
        showTimeMs = System.currentTimeMillis()
        bridge.pause()
        bridge.setIGMVisible(true)
        composeView?.let { v ->
            // A bezel keeps this window up and correct between menus, so there is no stale frame to
            // hide and the reveal below would only flash it away and back.
            val alreadyDrawing = v.visibility == View.VISIBLE
            if (!attachIfNeeded(v)) return@let
            // The surface still holds the last frame drawn before the menu was hidden, and the
            // compositor shows it the moment the window is up: without this the previous
            // screenshot is on screen until Compose submits a replacement. Stay transparent until
            // that frame is about to land.
            v.visibility = View.VISIBLE
            runCatching {
                activity.windowManager.updateViewLayout(
                    v, panelParams(focusable = true, alpha = if (alreadyDrawing) 1f else 0f),
                )
            }
            if (!alreadyDrawing) revealOnceDrawn(v)
            v.requestFocus()
            ViewCompat.getWindowInsetsController(v)?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private var pendingReveal: ViewTreeObserver.OnPreDrawListener? = null

    private fun revealOnceDrawn(view: ComposeView) {
        pendingReveal?.let { view.viewTreeObserver.removeOnPreDrawListener(it) }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                pendingReveal = null
                // This frame draws the current menu, so the window can carry it.
                if (showing) {
                    runCatching {
                        activity.windowManager.updateViewLayout(view, panelParams(focusable = true))
                    }
                }
                return true
            }
        }
        pendingReveal = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    fun hide() {
        if (!showing) return
        suspendedForNativeMenu = false
        hideWindow()
        controller.closeMenu()
        bridge.unpause()
        onHidden?.invoke()
    }

    private fun hideWindow() {
        showing = false
        bridge.setIGMVisible(false)
        composeView?.let { v ->
            if (!attached) return@let
            runCatching { activity.windowManager.updateViewLayout(v, panelParams(focusable = false)) }
            // A bezel outlives the menu, so the view only goes away when there is nothing to draw.
            v.visibility =
                if (controller.overlayPicker.activeImage.value != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Cannoli owns which overlay is in force, so the picker drives RetroArch directly, then stages
     * the keys it touched into the settings tree's existing dirty set. Persistence and the choice
     * between platform and game both come from the save prompt on the way out, so an overlay is
     * scoped exactly like every other setting rather than having a path of its own.
     *
     * The list carries a leading None so switching the bezel off is a move like any other rather
     * than a separate action, which is why every index below is offset by one.
     */
    /**
     * The window stays attached while the game runs and only its content view is hidden, so a
     * bezel needs that view shown even with no menu up. It is already non-focusable and
     * non-touchable in that state, so nothing reaches it and input still goes to the game.
     */
    private fun showOverlayLayer() {
        if (showing) return
        composeView?.let { v ->
            if (!attachIfNeeded(v, focusable = false)) return@let
            v.visibility = if (controller.overlayPicker.activeImage.value != null) View.VISIBLE else View.GONE
        }
    }

    // What was in force when the settings tree was entered, so Discard can put it back. The tree is
    // the unit of undo here, not the picker: leaving the picker is plain navigation.
    private var overlayBeforeEdit: String? = null

    private fun attachOverlayPicker() {
        val picker = controller.overlayPicker
        picker.title.value = uiContext.getString(R.string.igm_overlay)
        // A bezel outlives the session it was chosen in, so the stored pick is drawn from the start
        // rather than waiting for someone to open the menu.
        bridge.storedOverlayName()?.let { name ->
            picker.activeImage.value = overlayImageFor(name)
            bridge.cannoliOverlayName = name
        }
        overlayBeforeEdit = bridge.cannoliOverlayName
        showOverlayLayer()

        // Staged so the change joins the save prompt on the way out of the settings tree, which is
        // what gives an overlay the same platform-or-game scope every other setting has.
        picker.stagedKeys = setOf(EmbeddedRetroArchBridge.KEY_OVERLAY)

        bridge.onCannoliSaved = {
            overlayBeforeEdit = bridge.cannoliOverlayName
            picker.canRestore.value = bridge.overridesAtGame(EmbeddedRetroArchBridge.KEY_OVERLAY)
        }
        bridge.onCannoliRevert = {
            bridge.cannoliOverlayName = overlayBeforeEdit
            picker.activeImage.value = overlayBeforeEdit?.let { overlayImageFor(it) }
            picker.canRestore.value = bridge.overridesAtGame(EmbeddedRetroArchBridge.KEY_OVERLAY)
            showOverlayLayer()
        }

        picker.onRestoreDefault = {
            // The platform's answer, which is what this game will show once it stops giving its own.
            val inherited = bridge.restoreOverlayDefault()
            picker.activeImage.value = inherited?.let { overlayImageFor(it) }
            picker.selected.value = inherited ?: uiContext.getString(dev.cannoli.ui.R.string.value_none)
            picker.canRestore.value = false
            showOverlayLayer()
        }

        picker.onRefresh = {
            picker.canRestore.value = bridge.overridesAtGame(EmbeddedRetroArchBridge.KEY_OVERLAY)
            val none = uiContext.getString(dev.cannoli.ui.R.string.value_none)
            // Opening the picker is the one moment worth paying for a fresh scan, so a folder
            // added mid-session shows up without relaunching.
            picker.items.value = listOf(none) + bridge.rescanOverlays()
            picker.selected.value = picker.items.value.drop(1)
                .firstOrNull { overlayImageFor(it) == picker.activeImage.value } ?: none
        }

        // Cannoli draws the bezel, so choosing one is a state change and nothing more: no setting to
        // write, no runloop to wake, no pause to fight. Compose redraws and it is on screen.
        picker.onPreview = { index ->
            val name = picker.items.value.getOrNull(index)?.takeIf { index > 0 }
            bridge.pickOverlay(name)
            picker.activeImage.value = name?.let { overlayImageFor(it) }
            showOverlayLayer()
        }

    }

    private fun overlayImageFor(name: String): String? =
        if (cannoliRoot.isEmpty()) null
        else OverlayCatalog.resolveImage(File(OverlayCatalog.platformDir(File(cannoliRoot), platformTag), name))
            ?.absolutePath

    @Composable
    private fun IGMContent() {
        val colors = cannoliColorsFromHex(
            colorHighlight, colorText, colorHighlightText, colorAccent, colorTitle,
        )
        CannoliTheme(fontFamily = fontFamily) {
            CompositionLocalProvider(LocalCannoliColors provides colors) {
                CannoliIGM(
                    screen = controller.currentScreen,
                    config = hostConfig,
                    gameTitle = controller.gameTitle,
                    menuOptions = controller.buildMenuOptions(),
                    selectedSlot = controller.selectedSlotIndex.intValue,
                    slotThumbnail = controller.slotThumbnail.value,
                    slotThumbnailLoaded = controller.slotThumbnailLoaded.value,
                    slotExists = controller.slotExists.value,
                    slotOccupied = controller.slotOccupied.value,
                    undoAction = controller.undoAction.value,
                    settingsItems = controller.settingsItems.value,
                    previewTitle = controller.overlayPicker.title.value,
                    previewItems = controller.overlayPicker.items.value,
                    previewCanRestore = controller.overlayPicker.canRestore.value,
                    settingsCanRestore = controller.settingsCanRestore.value,
                    settingsCanReorder = controller.settingsCanReorder.value,
                    settingsCanRemovePass = controller.settingsCanRemovePass.value,
                    settingsReordering = controller.settingsReordering.value,
                    overlayImage = controller.overlayPicker.activeImage.value,
                    cheatItems = controller.cheatItems.value,
                    cheatVisibleItems = controller.cheatVisibleItems.value,
                    cheatFilter = controller.cheatFilter.value,
                    cheatHasRemembered = controller.cheatHasRemembered.value,
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
