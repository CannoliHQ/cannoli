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

internal fun localeContext(activity: Activity, tag: String): Context {
    if (tag.isEmpty()) return activity
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return activity
    return ContextThemeWrapper(activity, IGM_OVERLAY_THEME).apply {
        applyOverrideConfiguration(Configuration().apply { setLocales(LocaleList(locale)) })
    }
}

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
    private val uiContext: Context = localeContext(activity, localeTag)

    val controller = IGMController(bridge, gameTitle, SaveSlotStore(stateBasePath))

    var onOsdMessage: ((String) -> Unit)? = null

    var onWindowAttached: (() -> Unit)? = null

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

        bridge.onIgmTrigger = { show() }

        bridge.onDebugKey = { keycode ->
            controller.handleKeyDown(keycode)
        }

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
            rootTitle = uiContext.getString(R.string.settings_title),
            on = uiContext.getString(R.string.value_on),
            off = uiContext.getString(R.string.igm_off),
            restartHint = uiContext.getString(R.string.igm_restart_hint),
            savePlatform = uiContext.getString(R.string.igm_save_platform, platformName),
            saveGame = uiContext.getString(R.string.igm_save_game),
            dontSave = uiContext.getString(R.string.igm_dont_save),
            resetTitle = uiContext.getString(R.string.igm_reset_title),
            resetPlatform = uiContext.getString(R.string.igm_reset_platform, platformName),
            resetGame = uiContext.getString(R.string.igm_reset_game),
            emulator = uiContext.getString(R.string.igm_emulator),
            shortcuts = uiContext.getString(R.string.igm_shortcuts_title),
            buttonOverrides = uiContext.getString(R.string.igm_button_overrides),
            controllerType = uiContext.getString(R.string.igm_controller_type),
            playerController = { uiContext.getString(R.string.igm_player_controller, it) },
            shaderApplied = uiContext.getString(R.string.igm_shader_applied),
            shaderEnabled = uiContext.getString(R.string.igm_shader_enabled),
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
            shaderNone = uiContext.getString(R.string.value_none),
            custom = uiContext.getString(R.string.igm_curated_custom),
            infoCore = uiContext.getString(R.string.igm_info_core),
            infoCoreVersion = uiContext.getString(R.string.igm_info_core_version),
            infoRenderer = uiContext.getString(R.string.igm_info_renderer),
            infoContent = uiContext.getString(R.string.igm_info_content),
            infoSave = uiContext.getString(R.string.igm_info_save),
            infoAchievements = uiContext.getString(R.string.igm_info_achievements),
            achievementsHardcore = uiContext.getString(R.string.igm_achievements_hardcore),
            achievementsSoftcore = uiContext.getString(R.string.igm_achievements_softcore),
            achievementsUnrecognised = uiContext.getString(R.string.igm_achievements_unrecognised),
            achievementsNone = uiContext.getString(R.string.igm_achievements_none),
            achievementsOffline = uiContext.getString(R.string.igm_achievements_offline),
            achievementsOfflineCached = { uiContext.getString(R.string.ach_offline_cached, it) },
            infoGameId = uiContext.getString(R.string.igm_info_game_id),
            infoHash = uiContext.getString(R.string.igm_info_hash),
            curatedCategoryTitles = mapOf(
                CuratedCatalog.CATEGORY_VIDEO to uiContext.getString(R.string.igm_video),
                CuratedCatalog.CATEGORY_EMULATOR to uiContext.getString(R.string.igm_emulator),
                CuratedCatalog.CATEGORY_ADVANCED to uiContext.getString(R.string.settings_advanced),
                CuratedCatalog.CATEGORY_INFO to uiContext.getString(R.string.igm_info),
                CuratedCatalog.CATEGORY_OVERLAY to uiContext.getString(R.string.igm_overlay),
                CuratedCatalog.CATEGORY_INPUT to uiContext.getString(R.string.settings_input),
                CuratedCatalog.CATEGORY_SHADER to uiContext.getString(R.string.label_shaders),
            ),
            curatedRowLabels = mapOf(
                "curated_screen_scaling" to uiContext.getString(R.string.igm_curated_screen_scaling),
                "curated_screen_sharpness" to uiContext.getString(R.string.igm_curated_screen_sharpness),
                "curated_max_ff_speed" to uiContext.getString(R.string.igm_curated_max_ff_speed),
                "curated_ff_mute" to uiContext.getString(R.string.igm_curated_ff_mute),
                "curated_rewind" to uiContext.getString(R.string.label_rewind),
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
                "on" to uiContext.getString(R.string.value_on),
                "off" to uiContext.getString(R.string.igm_off),
            ),
        )

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
                        } else {
                            controller.handleKeyDown(keyCode, event.repeatCount > 0)
                        }
                    }
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

    private fun attachIfNeeded(view: ComposeView, focusable: Boolean = true): Boolean {
        if (attached) return true
        attached = runCatching {
            activity.windowManager.addView(view, panelParams(focusable = focusable))
        }.isSuccess
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

    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private fun showWindow() {
        showing = true
        showTimeMs = System.currentTimeMillis()
        bridge.pause()
        bridge.setIGMVisible(true)
        onVisibilityChanged?.invoke(true)
        composeView?.let { v ->
            val alreadyDrawing = v.visibility == View.VISIBLE
            if (!attachIfNeeded(v)) return@let
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
        onVisibilityChanged?.invoke(false)
        composeView?.let { v ->
            if (!attached) return@let
            runCatching { activity.windowManager.updateViewLayout(v, panelParams(focusable = false)) }
            v.visibility =
                if (controller.overlayPicker.activeImage.value != null) View.VISIBLE else View.GONE
        }
    }

    private fun showOverlayLayer() {
        if (showing) return
        composeView?.let { v ->
            // A GONE panel stalls the launch transition 5s on Android 14.
            if (!attached && controller.overlayPicker.activeImage.value == null) return@let
            if (!attachIfNeeded(v, focusable = false)) return@let
            v.visibility = if (controller.overlayPicker.activeImage.value != null) View.VISIBLE else View.GONE
        }
    }

    private var overlayBeforeEdit: String? = null

    private fun attachOverlayPicker() {
        val picker = controller.overlayPicker
        picker.title.value = uiContext.getString(R.string.igm_overlay)
        bridge.storedOverlayName()?.let { name ->
            picker.activeImage.value = overlayImageFor(name)
            bridge.cannoliOverlayName = name
        }
        overlayBeforeEdit = bridge.cannoliOverlayName
        showOverlayLayer()

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
        bridge.onCannoliReset = {
            overlayBeforeEdit = bridge.cannoliOverlayName
            picker.activeImage.value = bridge.cannoliOverlayName?.let { overlayImageFor(it) }
            picker.canRestore.value = bridge.overridesAtGame(EmbeddedRetroArchBridge.KEY_OVERLAY)
            showOverlayLayer()
        }

        picker.onRestoreDefault = {
            val inherited = bridge.restoreOverlayDefault()
            picker.activeImage.value = inherited?.let { overlayImageFor(it) }
            picker.selected.value = inherited ?: uiContext.getString(dev.cannoli.ui.R.string.value_none)
            picker.canRestore.value = false
            showOverlayLayer()
        }

        picker.onRefresh = {
            picker.canRestore.value = bridge.overridesAtGame(EmbeddedRetroArchBridge.KEY_OVERLAY)
            val none = uiContext.getString(dev.cannoli.ui.R.string.value_none)
            picker.items.value = listOf(none) + bridge.rescanOverlays()
            picker.selected.value = picker.items.value.drop(1)
                .firstOrNull { overlayImageFor(it) == picker.activeImage.value } ?: none
        }

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
                    shortcutRows = (controller.currentScreen as? dev.cannoli.igm.IGMScreen.Shortcuts)
                        ?.let { controller.shortcutRows.value }.orEmpty(),
                    remapRows = (controller.currentScreen as? dev.cannoli.igm.IGMScreen.ButtonMappings)
                        ?.let { controller.remapRows.value }.orEmpty(),
                    remapBase = controller.remapBase.value,
                    remapNames = controller.remapNames.value,
                    players = controller.players.value,
                    previewTitle = controller.overlayPicker.title.value,
                    previewItems = controller.overlayPicker.items.value,
                    previewCanRestore = controller.overlayPicker.canRestore.value,
                    settingsCanRestore = controller.settingsCanRestore.value,
                    settingsCanReorder = controller.settingsCanReorder.value,
                    settingsCanRemovePass = controller.settingsCanRemovePass.value,
                    settingsCanReset = controller.settingsCanReset.value,
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
                    menuGlyph = controller.menuGlyph.value,
                )
            }
        }
    }
}
