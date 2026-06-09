package dev.cannoli.scorza.libretro

import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.igm.GuideFile
import dev.cannoli.igm.GuideType
import dev.cannoli.igm.IGMScreen
import dev.cannoli.igm.IGMSettings
import dev.cannoli.igm.IGMSettingsItem
import dev.cannoli.igm.IgmMenuAction
import dev.cannoli.igm.InGameMenuOptions
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.ShaderPipeline
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.SessionLog
import dev.cannoli.ui.STAR
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalCannoliColors
import androidx.compose.runtime.collectAsState
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.ui.components.OsdPosition
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class LibretroActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var portRouter: dev.cannoli.scorza.input.runtime.PortRouter
    @Inject lateinit var controllerBridge: dev.cannoli.scorza.input.runtime.ControllerBridge
    @Inject lateinit var screenInputRegistry: dev.cannoli.scorza.input.runtime.ScreenInputRegistry
    @Inject lateinit var inputDispatcher: dev.cannoli.scorza.input.runtime.InputDispatcher
    @Inject lateinit var menuNavigationPoller: dev.cannoli.scorza.input.runtime.MenuNavigationPoller
    @Inject lateinit var stickAutoRepeat: dev.cannoli.scorza.input.runtime.StickAutoRepeat
    @Inject lateinit var activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder
    @Inject lateinit var controllersViewModel: dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
    @Inject lateinit var bindingController: dev.cannoli.scorza.input.BindingController

    private lateinit var runner: LibretroRunner
    private lateinit var renderer: LibretroRenderer
    private lateinit var slotManager: SaveSlotManager
    private lateinit var overrideManager: OverrideManager
    private var glSurfaceView: GLSurfaceView? = null
    private var gameView: android.view.View? = null
    private var vsyncCallback: android.view.Choreographer.FrameCallback? = null
    private var es3Supported: Boolean = true

    private fun startVsyncPacer() {
        val view = glSurfaceView ?: return
        if (vsyncCallback != null) return
        val choreographer = android.view.Choreographer.getInstance()
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (vsyncCallback !== this) return
                view.requestRender()
                choreographer.postFrameCallback(this)
            }
        }
        vsyncCallback = cb
        choreographer.postFrameCallback(cb)
    }

    private fun stopVsyncPacer() {
        val cb = vsyncCallback ?: return
        android.view.Choreographer.getInstance().removeFrameCallback(cb)
        vsyncCallback = null
    }

    // Drain any in-flight retro_run on the GL thread before the main thread
    // proceeds. Used at pause boundaries so the renderer is fully idle before
    // we touch shared lifecycle state (audio, vsync pacer, screen-on flag).
    private fun syncGlThread() {
        val view = glSurfaceView ?: return
        val latch = CountDownLatch(1)
        view.queueEvent { latch.countDown() }
        latch.await(100L, TimeUnit.MILLISECONDS)
    }

    // Run a block on the GL thread (the same thread that called retro_init /
    // retro_run) and return its result. Libretro cores expect their callbacks
    // to be invoked from the thread that drives retro_run; calling them from
    // the main thread risks corrupting core state. Used for any core query
    // issued outside the render loop.
    private inline fun <T> runOnGlThread(timeoutMs: Long, default: T, crossinline block: () -> T): T {
        val view = glSurfaceView ?: return default
        val latch = CountDownLatch(1)
        var result: T = default
        view.queueEvent {
            result = block()
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    private var loading by mutableStateOf(true)
    private var revealed by mutableStateOf(false)
    private var missingBios by mutableStateOf<List<dev.cannoli.scorza.config.FirmwareEntry>>(emptyList())

    private val screenStack = mutableStateListOf<IGMScreen>()

    private var selectedSlotIndex by mutableIntStateOf(0)
    private var slotThumbnail by mutableStateOf<Bitmap?>(null)
    private var slotExists by mutableStateOf(false)
    private var slotOccupied by mutableStateOf(emptyList<Boolean>())
    private var cleaned = false
    private var autoSavedOnStop = false

    private var scalingMode by mutableStateOf(ScalingMode.CORE_REPORTED)
    private var screenEffect by mutableStateOf(ScreenEffect.NONE)
    private var sharpness by mutableStateOf(Sharpness.SHARP)
    private var debugHud by mutableStateOf(false)
    private var showFps by mutableStateOf(false)
    private var showFpsBaseline by mutableStateOf(false)
    private var maxFfSpeed by mutableIntStateOf(4)
    private var overlay by mutableStateOf("")
    private var overlayImages = emptyList<String>()

    private var shaderPreset by mutableStateOf("")
    private var shaderPresets = emptyList<String>()
    private var shaderParams by mutableStateOf(emptyList<ShaderParamItem>())

    private var coreOptions by mutableStateOf(emptyList<LibretroRunner.CoreOption>())
    private var coreCategories by mutableStateOf(emptyList<LibretroRunner.CoreOptionCategory>())
    private var coreRequiresHwRender = false
    private var controllerTypes by mutableStateOf(emptyList<LibretroRunner.ControllerType>())
    private var controllerTypeIndex by mutableIntStateOf(0)
    private var portDeviceTypes by mutableStateOf<Map<Int, Int>>(emptyMap())

    @Volatile
    private var activeInputRemap: Map<dev.cannoli.scorza.input.CanonicalButton, Int> = emptyMap()

    private var inputRemap by mutableStateOf<Map<dev.cannoli.scorza.input.CanonicalButton, Int>>(emptyMap())

    private var shortcutSource by mutableStateOf(OverrideSource.GLOBAL)
    private var shortcuts by mutableStateOf(mapOf<ShortcutAction, Set<Int>>())
    private var coreInfoText by mutableStateOf("")

    private var confirmButton = dev.cannoli.ui.ConfirmButton.EAST
    private var buttonLabelSet = dev.cannoli.ui.ButtonLabelSet.PLUMBER

    private var frontendSnapshot: OverrideManager.Settings? = null
    private var shaderParamsDirty = false
    private var lastShaderCycleMs = 0L
    private var platformBaseline: OverrideManager.Settings? = null

    private var diskCount by mutableIntStateOf(0)
    private var currentDiskIndex by mutableIntStateOf(0)
    private var diskLabels = emptyList<String>()

    private var raManager: RetroAchievementsManager? = null
    private var raInfoTick by mutableIntStateOf(0)

    private val raPausedIdleHandler = Handler(Looper.getMainLooper())
    private val raPausedIdleRunnable = object : Runnable {
        override fun run() {
            raManager?.idle()
            raInfoTick++
            if (renderer.paused) raPausedIdleHandler.postDelayed(this, 200L)
        }
    }

    private fun startRaPausedIdle() {
        raPausedIdleHandler.removeCallbacks(raPausedIdleRunnable)
        raPausedIdleHandler.post(raPausedIdleRunnable)
    }

    private fun stopRaPausedIdle() {
        raPausedIdleHandler.removeCallbacks(raPausedIdleRunnable)
    }

    private var audioSampleRate = 0
    private var fastForwarding by mutableStateOf(false)
    private var holdingFf = false

    private val pendingHoldHandler = Handler(Looper.getMainLooper())
    private val pendingHoldRunnable = Runnable { firePendingHold() }
    private var pendingHoldAction: ShortcutAction? = null
    private var pendingHoldPort = -1
    private var pendingHoldChord: Set<Int> = emptySet()

    private val audioStatsHandler = Handler(Looper.getMainLooper())
    private val audioStatsRunnable = object : Runnable {
        override fun run() {
            sessionLog.log("audio ${runner.getAudioDiagnostics()}")
            audioStatsHandler.postDelayed(this, 5000)
        }
    }

    private fun setFastForward(enabled: Boolean) {
        fastForwarding = enabled
        renderer.fastForwardFrames = if (enabled) maxFfSpeed else 0
        runner.setAudioMuted(enabled)
    }

    private fun performSaveAndQuit() {
        stopVsyncPacer()
        renderer.paused = true
        syncGlThread()
        runner.pauseAudio()
        if (stateBasePath.isNotEmpty()) slotManager.saveState(runner, slotManager.slots[0])
        quit()
    }

    private fun holdDurationFor(action: ShortcutAction): Long = when (action) {
        ShortcutAction.SAVE_AND_QUIT_HOLD -> 1250L
        else -> 0L
    }

    private fun holdOsdMessageFor(action: ShortcutAction): String? = when (action) {
        ShortcutAction.SAVE_AND_QUIT_HOLD -> "Hold To Save and Quit"
        else -> null
    }

    private fun schedulePendingHold(action: ShortcutAction, port: Int, chord: Set<Int>) {
        cancelPendingHold()
        pendingHoldAction = action
        pendingHoldPort = port
        pendingHoldChord = chord
        holdOsdMessageFor(action)?.let { osdController.show(it, OsdPosition.BottomCenter, durationMs = holdDurationFor(action) + 500L) }
        pendingHoldHandler.postDelayed(pendingHoldRunnable, holdDurationFor(action))
    }

    private fun cancelPendingHold() {
        if (pendingHoldAction == null) return
        pendingHoldHandler.removeCallbacks(pendingHoldRunnable)
        pendingHoldAction = null
        pendingHoldPort = -1
        pendingHoldChord = emptySet()
        osdController.clear()
    }

    private fun firePendingHold() {
        val action = pendingHoldAction ?: return
        pendingHoldAction = null
        pendingHoldPort = -1
        pendingHoldChord = emptySet()
        when (action) {
            ShortcutAction.SAVE_AND_QUIT_HOLD -> performSaveAndQuit()
            else -> Unit
        }
    }

    private fun checkPendingHoldRelease(port: Int) {
        if (pendingHoldAction == null || pendingHoldPort != port) return
        if (!portPressedKeys[port].containsAll(pendingHoldChord)) cancelPendingHold()
    }

    private enum class UndoType { SAVE, LOAD, RESET }
    private var undoType by mutableStateOf<UndoType?>(null)
    private var undoSlot: Slot? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private val clearUndoRunnable = Runnable { clearUndo() }
    @Inject lateinit var osdController: dev.cannoli.ui.components.OsdController
    private var gameTitle: String = ""
    private var corePath: String = ""
    private var romPath: String = ""
    private var originalRomPath: String? = null
    private var sramPath: String = ""
    private var stateBasePath: String = ""
    private var systemDir: String = ""
    private var saveDir: String = ""
    private var platformTag: String = ""
    private var gameBaseName: String = ""
    private var platformName: String = ""
    private var cannoliRoot: String = ""

    private val currentSlot get() = slotManager.slots[selectedSlotIndex]
    private val currentScreen get() = screenStack.lastOrNull()
    private val hasDiscs get() = diskCount > 1
    private val alwaysSaveOnQuit: Boolean get() = settings.alwaysSaveOnQuit

    @androidx.compose.runtime.Composable
    private fun MissingBiosScreen(entries: List<dev.cannoli.scorza.config.FirmwareEntry>) {
        val header = getString(R.string.dialog_missing_bios_header, "Cannoli/BIOS/$platformTag")
        val body = entries.joinToString(separator = "\n", prefix = "$header\n") { "• ${it.desc}" }
        dev.cannoli.ui.components.LaunchErrorDialog(
            message = body,
            buttonStyle = dev.cannoli.ui.ButtonStyle(labelSet = buttonLabelSet, confirmButton = confirmButton)
        )
    }

    private suspend fun maybeReportMissingBios(corePath: String, logs: List<String>): Boolean {
        val problemKeywords = listOf("missing", "not found", "no bios", "no such", "couldn't find", "could not find", "cannot find", "can't find", "required", "failed to load", "failed to open bios", "failed to open firmware", "unable to load", "unable to find")
        val matched = logs.any { line ->
            val lower = line.lowercase()
            ("bios" in lower || "firmware" in lower) && problemKeywords.any { it in lower }
        }
        if (!matched) return false
        val coreId = File(corePath).name.removeSuffix("_android.so")
        val entries = dev.cannoli.scorza.config.CoreInfoRepository(assets).getFirmwareFor(coreId)
        if (entries.isEmpty()) return false
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            missingBios = entries
        }
        return true
    }

    private fun diskLabel(index: Int): String =
        diskLabels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: getString(R.string.osd_disc_fallback, index + 1)

    @Volatile private var raHasAchievements = false

    private lateinit var sessionLog: SessionLog
    private lateinit var guideManager: GuideManager
    private var guideFiles by mutableStateOf(emptyList<GuideFile>())
    private var guidePageCount by mutableIntStateOf(0)
    private var guideScrollDir by mutableIntStateOf(0)
    private var guideScrollXDir by mutableIntStateOf(0)
    private var guidePageJump by mutableIntStateOf(0)
    private var guidePageJumpDir = 0
    private var guideScrollPos = 0
    private var guideScrollXPos = 0
    private var guideInitialScroll by mutableIntStateOf(0)
    private var guideInitialScrollX by mutableIntStateOf(0)
    private var infoScrollDir by mutableIntStateOf(0)

    private fun menuOptions() = InGameMenuOptions(
        hasDiscs,
        diskLabel(currentDiskIndex),
        raHasAchievements,
        guideFiles.isNotEmpty(),
        hasReassign = nonExcludedConnectedCount() > 1,
        quitLabel = if (alwaysSaveOnQuit) getString(R.string.igm_save_and_quit) else getString(R.string.igm_quit)
    )

    private fun nonExcludedConnectedCount(): Int =
        portRouter.snapshotEntries().count { !it.mapping.excludeFromGameplay }

    private fun refreshDiskInfo() {
        val hasCtl = runner.hasDiskControl()
        sessionLog.log("refreshDiskInfo: hasCtl=$hasCtl")
        if (!hasCtl) return
        val snapshot = runOnGlThread(200L, Triple(0, 0, emptyList<String>())) {
            val count = runner.getDiskCount()
            val index = runner.getDiskIndex()
            val labels = if (count > 1) (0 until count).map { runner.getDiskLabel(it) ?: "" } else emptyList()
            Triple(count, index, labels)
        }
        diskCount = snapshot.first
        currentDiskIndex = snapshot.second
        diskLabels = snapshot.third
        sessionLog.log("refreshDiskInfo: count=$diskCount index=$currentDiskIndex labels=${diskLabels.size}")
    }

    data class ShaderParamItem(
        val id: String, val description: String,
        val value: Float, val min: Float, val max: Float, val step: Float
    )

    companion object {
        private val FF_SPEEDS = listOf(2, 3, 4, 6, 8)

        // Substrings (lowercase) matched against "<key> <desc>" for cores declaring hw_render=true.
        // These options only take effect with a hardware GL/Vulkan context, which the built-in
        // runner does not provide, so we hide them rather than let users toggle no-ops.
        private val HW_RENDER_GATED_PATTERNS = listOf(
            "resolution",
            "internal_res",
            "upscal",
            "msaa",
            "multisamp",
            "supersamp",
            "antialias",
            "anti_alias",
            "anti-alias",
            "anisotropic",
            "widescreen_hack",
            "widescreen hack",
            "render_scal",
            "render scal",
            "texture_scaling",
            "texture scaling",
            "framebuffer scal",
            "framebuffer_scal",
            "pgxp",
            "texture_filt",
            "texture filter",
            "bilinear",
            "trilinear",
        )
        private const val TRIGGER_PRESS_THRESHOLD = 0.5f
        private const val TRIGGER_RELEASE_THRESHOLD = 0.3f
        @Volatile var isRunning = false

        // Last-resort keycode -> nav button mapping for IGM nav, used only when the device
        // is not enrolled in v2 PortRouter (no DeviceMapping available).
        private val NAV_FALLBACK_KEY_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "btn_south",
            KeyEvent.KEYCODE_BUTTON_B to "btn_east",
            KeyEvent.KEYCODE_BUTTON_X to "btn_west",
            KeyEvent.KEYCODE_BUTTON_Y to "btn_north",
            KeyEvent.KEYCODE_BUTTON_L1 to "btn_l",
            KeyEvent.KEYCODE_BUTTON_R1 to "btn_r",
            KeyEvent.KEYCODE_BUTTON_L2 to "btn_l2",
            KeyEvent.KEYCODE_BUTTON_R2 to "btn_r2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "btn_l3",
            KeyEvent.KEYCODE_BUTTON_THUMBR to "btn_r3",
            KeyEvent.KEYCODE_BUTTON_START to "btn_start",
            KeyEvent.KEYCODE_BUTTON_SELECT to "btn_select",
            KeyEvent.KEYCODE_DPAD_UP to "btn_up",
            KeyEvent.KEYCODE_DPAD_DOWN to "btn_down",
            KeyEvent.KEYCODE_DPAD_LEFT to "btn_left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "btn_right",
        )
    }

    private fun push(screen: IGMScreen) { screenStack.add(screen) }

    private fun pop() {
        if (screenStack.isNotEmpty()) screenStack.removeAt(screenStack.lastIndex)
    }

    private fun replaceTop(screen: IGMScreen) {
        if (screenStack.isNotEmpty()) screenStack[screenStack.lastIndex] = screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val args = dev.cannoli.scorza.launcher.LaunchArgs.from(intent) ?: run { finish(); return }
        gameTitle = args.gameTitle.removePrefix("$STAR ")
        corePath = args.corePath
        romPath = args.romPath
        originalRomPath = args.originalRomPath
        sramPath = args.sramPath
        stateBasePath = args.statePath
        systemDir = args.systemDir
        saveDir = args.saveDir
        platformTag = args.platformTag
        platformName = args.platformName
        cannoliRoot = args.cannoliRoot
        val coreName = File(corePath).nameWithoutExtension
        sessionLog = SessionLog(
            enabled = dev.cannoli.scorza.util.LoggingPrefs.session,
            cannoliRoot = cannoliRoot,
            coreName = coreName,
            corePath = corePath,
            romPath = romPath,
            gameTitle = gameTitle
        )

        if (savedInstanceState != null) {
            sessionLog.log("onCreate: savedInstanceState != null, finishing (isChangingConfigurations=$isChangingConfigurations, isTaskRoot=$isTaskRoot)")
            sessionLog.close()
            finish()
            return
        }

        sessionLog.log("onCreate started")
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val reqGlEs = am.deviceConfigurationInfo.reqGlEsVersion
        val glEsMajor = reqGlEs ushr 16
        val glEsMinor = reqGlEs and 0xFFFF
        es3Supported = reqGlEs >= 0x30000
        ShaderPipeline.es3Supported = es3Supported
        sessionLog.log("device GLES: 0x${Integer.toHexString(reqGlEs)} (${glEsMajor}.${glEsMinor}) es3Supported=$es3Supported")
        confirmButton = activeMappingHolder.active.value.confirmButton()
        buttonLabelSet = activeMappingHolder.active.value.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER)
        isRunning = true
        wireBindingController()
        window.setBackgroundDrawableResource(android.R.color.black)
        goFullscreen()
        sessionLog.log("game_title=$gameTitle")
        sessionLog.log("system_dir=$systemDir")
        sessionLog.log("save_dir=$saveDir")
        sessionLog.log("platform_tag=$platformTag")
        slotManager = SaveSlotManager(stateBasePath)
        guideManager = GuideManager(cannoliRoot, platformTag, File(romPath).nameWithoutExtension)
        runner = LibretroRunner()

        val colors = CannoliColors(
            highlight = hexToColor(args.colorHighlight) ?: Color.White,
            text = hexToColor(args.colorText) ?: Color.White,
            highlightText = hexToColor(args.colorHighlightText) ?: Color.Black,
            accent = hexToColor(args.colorAccent) ?: Color.White,
            title = hexToColor(args.colorTitle) ?: Color.White,
            background = hexToColor(args.colorBackground) ?: Color.Black,
            statusBar = hexToColor(args.colorStatusBar) ?: Color.White
        )

        val appFonts = (application as dev.cannoli.scorza.CannoliApp).appFonts
        val fontFamily = run {
            val fontKey = args.font
            when (fontKey) {
                "default" -> appFonts.mplus1Code
                "the_og" -> appFonts.bpReplay
                else -> {
                    val fontFile = File(dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).configFonts, fontKey)
                    if (fontFile.exists()) {
                        try {
                            val typeface = android.graphics.Typeface.createFromFile(fontFile)
                            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
                        } catch (_: Exception) { appFonts.mplus1Code }
                    } else appFonts.mplus1Code
                }
            }
        }

        setContent {
            CannoliTheme(fontFamily = fontFamily, iconFontFamily = appFonts.mplus1Code) {
                CompositionLocalProvider(
                    LocalCannoliColors provides colors,
                    dev.cannoli.scorza.input.screen.compose.LocalScreenInputRegistry provides screenInputRegistry,
                ) {
                    if (missingBios.isNotEmpty()) {
                        MissingBiosScreen(missingBios)
                    } else if (loading) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        val screen = if (revealed) currentScreen else null
                        // Push the screen's input handler into the registry. InputDispatcher's
                        // wired callbacks dispatch to this handler; Buttons and Shortcuts
                        // override ScreenInputHandler.onRawKeyDown to capture raw events.
                        val igmHandler = androidx.compose.runtime.remember(screen) {
                            screen?.let { igmHandlerFor(it) }
                        }
                        if (igmHandler != null) {
                            dev.cannoli.scorza.input.screen.compose.ScreenInput(igmHandler)
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            LibretroScreen(
                                glSurfaceView = gameView!!,
                                gameTitle = gameTitle,
                                screen = screen,
                                menuOptions = menuOptions(),
                                selectedSlot = currentSlot,
                                slotThumbnail = slotThumbnail,
                                slotExists = slotExists,
                                slotOccupied = slotOccupied,
                                undoLabel = when (undoType) {
                                    UndoType.SAVE -> "Undo Save"
                                    UndoType.LOAD -> "Undo Load"
                                    UndoType.RESET -> "Undo Reset"
                                    null -> null
                                },
                                settingsItems = if (screen is IGMScreen.Menu) emptyList() else buildSettingsItems(),
                                coreInfo = coreInfoText,
                                debugHud = debugHud,
                                showFps = showFps,
                                renderer = renderer,
                                runner = runner,
                                audioSampleRate = audioSampleRate,
                                osdController = osdController,
                                fastForwarding = fastForwarding,
                                settings = settings,
                                guideFiles = guideFiles,
                                guidePageCount = guidePageCount,
                                guideScrollDir = guideScrollDir,
                                guideScrollXDir = guideScrollXDir,
                                guidePageJump = guidePageJump,
                                guidePageJumpDir = guidePageJumpDir,
                                guideInitialScroll = guideInitialScroll,
                                guideInitialScrollX = guideInitialScrollX,
                                onGuideScrollChanged = { y, x -> guideScrollPos = y; guideScrollXPos = x },
                                infoScrollDir = infoScrollDir,
                                gameInfo = GameInfo(
                                    coreName = coreInfoText,
                                    romPath = romPath,
                                    savePath = sramPath.takeIf { java.io.File(it).exists() },
                                    rootPrefix = cannoliRoot,
                                    originalRomPath = originalRomPath,
                                    rendererName = renderer.backendName,
                                    raStatus = raManager?.let { ra ->
                                        if (ra.isLoggedIn) {
                                            "${ra.username} (${ra.getStatus()})"
                                        } else null
                                    },
                                    raGameId = raManager?.let { ra ->
                                        val id = ra.gameId
                                        if (id > 0) {
                                            val title = ra.gameTitle
                                            if (title.isNotEmpty()) "$id — $title" else "$id"
                                        } else null
                                    },
                                    raDetection = raInfoTick.let { raManager?.takeIf { it.isLoggedIn }?.getDetectionStatus() }
                                ),
                                activeMapping = activeMappingHolder.active.collectAsState().value,
                                controllersViewModel = controllersViewModel,
                                inputRemapHasChanges = inputRemap.isNotEmpty(),
                            )
                            if (!revealed) {
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                            }
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (missingBios.isNotEmpty()) { finish(); return }
                if (loading) return
                if (screenStack.isEmpty()) openMenu() else pop()
                if (screenStack.isEmpty()) { stopRaPausedIdle(); renderer.paused = false; runner.resumeAudio(); startVsyncPacer() }
            }
        })

        val resumeSlot = args.resumeSlot
        val activity = this
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sessionLog.log("loadCore: $corePath")
            if (!runner.loadCore(corePath)) {
                sessionLog.logError("loadCore FAILED for $corePath")
                val logs = runner.getCoreLogs()
                for (line in logs) sessionLog.log("  core: $line")
                val shownBios = maybeReportMissingBios(corePath, logs)
                sessionLog.close()
                if (!shownBios) withContext(kotlinx.coroutines.Dispatchers.Main) { finish() }
                return@launch
            }
            runner.init(systemDir, saveDir)
            sessionLog.log("runner.init completed")
            val coreInfoRepoForHw = dev.cannoli.scorza.config.CoreInfoRepository(assets)
            val coreIdForHw = File(corePath).name.removeSuffix("_android.so")
            coreRequiresHwRender = coreInfoRepoForHw.requiresHwRender(coreIdForHw)
            if (coreRequiresHwRender) sessionLog.log("core declares hw_render=true; hiding GPU-scaling options")
            val coreBaseName = File(corePath).nameWithoutExtension
            gameBaseName = if (romPath.isNotEmpty()) File(romPath).nameWithoutExtension else ""
            overrideManager = OverrideManager(cannoliRoot, platformTag, gameBaseName, coreBaseName)
            for ((key, value) in overrideManager.loadCoreOptions()) {
                runner.setCoreOption(key, value)
            }
            val romFile = File(romPath)
            sessionLog.log("ROM validation: exists=${romFile.exists()} readable=${romFile.canRead()} size=${romFile.length()} ext=${romFile.extension}")
            val biosDir = File(systemDir)
            val biosFiles = biosDir.listFiles()?.map { it.name } ?: emptyList()
            sessionLog.log("BIOS dir ($systemDir): $biosFiles")
            val coreOpts = runner.getCoreOptions().map { "${it.key}=${it.selected}" }
            sessionLog.log("Core options: $coreOpts")
            sessionLog.log("loadGame: $romPath")
            val avInfo = runner.loadGame(romPath)
            if (avInfo == null) {
                sessionLog.logError("loadGame returned null for $romPath")
                val gameLogs = runner.getCoreLogs()
                for (line in gameLogs) sessionLog.log("  core: $line")
                val shownBios = maybeReportMissingBios(corePath, gameLogs)
                sessionLog.close()
                runner.deinit()
                if (!shownBios) withContext(kotlinx.coroutines.Dispatchers.Main) { finish() }
                return@launch
            }
            sessionLog.log("loadGame succeeded: fps=${avInfo.fps} sampleRate=${avInfo.sampleRate}")
            val memDescs = runner.getMemoryDescriptors()
            sessionLog.log("Memory descriptors: count=${memDescs.size}")
            for (line in memDescs) sessionLog.log("  mmap: $line")
            if (sramPath.isNotEmpty() && File(sramPath).exists()) runner.loadSRAM(sramPath)
            if (resumeSlot >= 0) {
                val slot = slotManager.slots.getOrNull(resumeSlot)
                if (slot != null && slotManager.stateExists(slot)) {
                    slotManager.loadState(runner, slot)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { selectedSlotIndex = resumeSlot }
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val (coreName, coreVersion) = runner.getSystemInfo()
                coreInfoText = if (coreVersion.isNotEmpty()) "$coreName $coreVersion" else coreName
                coreOptions = loadVisibleCoreOptions()
                coreCategories = runner.getCoreCategories()

                loadOverrides()
                controllerTypes = runner.getControllerTypes(0).filter { it.id != 0 }
                applyPortDeviceTypes()
                scanOverlayImages()
                copyBundledShaders()
                scanShaderPresets()

                audioSampleRate = avInfo.sampleRate
                sessionLog.log("audio init: requested sampleRate=${avInfo.sampleRate}")
                runner.initAudio(avInfo.sampleRate, avInfo.fps)
                runner.setAudioMuted(true)
                if (dev.cannoli.scorza.util.LoggingPrefs.session) {
                    sessionLog.log("audio ${runner.getAudioDiagnostics()}")
                    audioStatsHandler.postDelayed(audioStatsRunnable, 5000)
                }

                val shaderCacheDir = File(cacheDir, "shader_cache")
                ShaderPipeline.cacheDir = shaderCacheDir

                fun configureBackend(backend: LibretroRenderer) {
                    backend.coreAspectRatio = runner.getAspectRatio()
                    backend.scalingMode = scalingMode
                    backend.sharpness = sharpness
                    backend.screenEffect = screenEffect
                    backend.debugHud = debugHud
                    backend.overlayPath = resolveOverlayPath()
                    backend.shaderPresetPath = resolveShaderPresetPath()
                    backend.portraitMarginPx = settings.portraitMarginPx
                }

                val glesBackend = LibretroRenderer(runner)
                glesBackend.coreTargetFps = avInfo.fps
                if (dev.cannoli.scorza.util.LoggingPrefs.session) {
                    glesBackend.logger = { msg -> sessionLog.log(msg) }
                    ShaderPipeline.logger = { msg -> sessionLog.log(msg) }
                }
                configureBackend(glesBackend)
                var startupCountdown = 10
                // HACK: FBNeo has a bug where vertical arcade games initialize with wrong
                // framebuffer orientation despite reporting correct rotation. Toggling the
                // vertical-mode option off→on→off forces FBNeo to reinitialize its video
                // pipeline correctly. Done during hidden startup frames so the user never
                // sees the intermediate state. Remove when FBNeo fixes this upstream.
                var verticalReinitPhase = 0
                val verticalToggle = prepareVerticalModeReinit()
                glesBackend.onFrameRendered = {
                    if (startupCountdown > 0 && --startupCountdown == 0) {
                        runner.setAudioMuted(false)
                        sessionLog.log("startup reveal: unmute and reveal")
                        runOnUiThread { revealed = true }
                    }
                    if (verticalToggle != null) {
                        when (verticalReinitPhase) {
                            5 -> { verticalToggle.first(); verticalReinitPhase++ }
                            125 -> { verticalToggle.second(); verticalReinitPhase++ }
                            else -> if (verticalReinitPhase < 126) verticalReinitPhase++
                        }
                    }
                }
                renderer = glesBackend
                pushShaderParamsToRenderer()

                val eglLog: (String) -> Unit = { msg -> if (dev.cannoli.scorza.util.LoggingPrefs.session) sessionLog.log(msg) }
                val glVersion = if (es3Supported) 3 else 2
                glSurfaceView = GLSurfaceView(activity).apply {
                    setEGLContextClientVersion(glVersion)
                    setEGLConfigChooser(LoggingEglConfigChooser(glVersion, eglLog))
                    setEGLContextFactory(LoggingEglContextFactory(glVersion, eglLog))
                    setRenderer(glesBackend)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: android.view.View) { eglLog("GLSurfaceView attached to window") }
                        override fun onViewDetachedFromWindow(v: android.view.View) { eglLog("GLSurfaceView detached from window") }
                    })
                    val requestedFps = avInfo.fps.toFloat()
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(h: android.view.SurfaceHolder) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                try {
                                    h.surface.setFrameRate(
                                        requestedFps,
                                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                                    )
                                    val achieved = activity.display?.refreshRate ?: -1f
                                    sessionLog.log("setFrameRate requested=$requestedFps achieved=$achieved")
                                } catch (t: Throwable) {
                                    sessionLog.log("setFrameRate threw: ${t.message}")
                                }
                            }
                            val displayHz = activity.display?.refreshRate ?: 60f
                            val mismatch = kotlin.math.abs(displayHz - requestedFps) / requestedFps
                            glesBackend.lockedToVsync = mismatch < 0.02f
                            sessionLog.log("vsync lock: displayHz=$displayHz coreFps=$requestedFps mismatch=${"%.4f".format(mismatch)} locked=${glesBackend.lockedToVsync}")
                        }
                        override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
                    })
                }
                gameView = glSurfaceView
                startVsyncPacer()
                sessionLog.log("renderer initialized: ${renderer.backendName}")

                loading = false
                sessionLog.log("render loop starting")

                val raUser = args.raUsername
                val raToken = args.raToken
                val raPassword = args.raPassword
                val consoleId = RetroAchievementsManager.CONSOLE_MAP[platformTag.uppercase()]
                sessionLog.log("RA init: user=${raUser.isNotEmpty()} token=${raToken.isNotEmpty()} password=${raPassword.isNotEmpty()} consoleId=$consoleId platformTag=$platformTag")
                if (consoleId != null && raUser.isNotEmpty() && (raToken.isNotEmpty() || raPassword.isNotEmpty())) {
                    val raGameIdOverride = args.raGameId ?: 0
                    var tokenRetryAttempted = false
                    lateinit var ra: RetroAchievementsManager
                    ra = RetroAchievementsManager(
                        context = activity,
                        cacheDir = java.io.File(cacheDir, "ra_cache"),
                        onEvent = { _, title, _, _ ->
                            raHasAchievements = true
                            showOsd("\uDB81\uDD38 $title", OsdPosition.BottomCenterLow)
                        },
                        onLogin = { success, nameOrError, newToken ->
                            sessionLog.log("RA onLogin: success=$success name=$nameOrError tokenReceived=${newToken != null}")
                            if (success && newToken != null) {
                                if (tokenRetryAttempted) {
                                    settings.raToken = newToken
                                    settings.flush()
                                    sessionLog.log("RA token refreshed via password retry")
                                }
                                scheduleRaStartupOsd(nameOrError)
                            } else if (!tokenRetryAttempted && raPassword.isNotEmpty()) {
                                tokenRetryAttempted = true
                                sessionLog.log("RA token login failed, retrying with password")
                                ra.loginWithPassword(raUser, raPassword)
                            } else {
                                sessionLog.logError("RA login failed: $nameOrError")
                                if (ra.isOnline) {
                                    settings.raToken = ""
                                    settings.raPassword = ""
                                    settings.flush()
                                    sessionLog.log("RA credentials cleared -- user must re-authenticate")
                                }
                                showOsd(getString(R.string.ra_login_failed), OsdPosition.TopCenter)
                            }
                        },
                        onSyncStatus = { msg -> showOsd(msg, OsdPosition.TopStart) },
                        onDetectionReady = { onRaDetectionReady() },
                        logger = { msg -> sessionLog.log(msg) }
                    )
                    ra.init()
                    if (raToken.isNotEmpty()) {
                        ra.loginWithToken(raUser, raToken)
                    } else {
                        tokenRetryAttempted = true
                        ra.loginWithPassword(raUser, raPassword)
                    }
                    if (raGameIdOverride > 0) {
                        ra.loadGameById(raGameIdOverride, consoleId)
                        sessionLog.log("RA loadGameById: id=$raGameIdOverride consoleId=$consoleId")
                    } else {
                        ra.loadGame(romPath, consoleId)
                        sessionLog.log("RA loadGame: romPath=$romPath consoleId=$consoleId")
                    }
                    if (resumeSlot >= 0) {
                        ra.setPendingReset()
                        sessionLog.log("RA setPendingReset (resumeSlot=$resumeSlot)")
                    }
                    raManager = ra
                    slotManager.raManager = ra
                }
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestAllmIfTv()
    }

    private fun requestAllmIfTv() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as? UiModeManager ?: return
        if (uiModeManager.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION) return
        val display = window.decorView.display ?: return
        if (!display.isMinimalPostProcessingSupported) return
        window.setPreferMinimalPostProcessing(true)
        sessionLog.log("ALLM requested (minimal post-processing)")
    }

    // --- Input ---

    private val triggerL2HeldDevices = mutableSetOf<Int>()
    private val triggerR2HeldDevices = mutableSetOf<Int>()
    private val portConsumedKeys = Array(LibretroRunner.MAX_PORTS) { mutableSetOf<Int>() }
    private val portPressedKeys = Array(LibretroRunner.MAX_PORTS) { mutableSetOf<Int>() }

    private fun handleMenuMotion(event: android.view.MotionEvent): Boolean {
        // Route through the dispatcher first so the evaluator can react to Hat bindings (e.g.
        // Retroid Pocket Controller's D-pad uses hat axes 15/16). The dispatcher will fire
        // BTN_UP/DOWN/LEFT/RIGHT via the wired callback; PortRouter held-state is updated so
        // MenuNavigationPoller drives auto-repeat. Stick fallback below covers devices whose
        // mapping doesn't bind sticks as canonical navigation.
        val consumed = if (::inputDispatcher.isInitialized)
            inputDispatcher.handleMotionEvent(event) else false
        if (::stickAutoRepeat.isInitialized) stickAutoRepeat.handleMotion(event, dispatcherHandled = consumed)

        // Guide-screen special case: it owns its own continuous scroll mechanism (guideScrollDir
        // polled from a separate runnable). Reset it when BOTH stick AND hat return to neutral.
        // Checking only the stick would erase the scroll-dir on every hat motion event for
        // hat-only D-pads (Retroid built-in), wiping the value handleGuideInput just set.
        if (currentScreen is IGMScreen.Guide) {
            val stickX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val stickY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            if (kotlin.math.abs(stickX) < 0.5f && kotlin.math.abs(stickY) < 0.5f &&
                kotlin.math.abs(hatX) < 0.5f && kotlin.math.abs(hatY) < 0.5f) {
                guideScrollDir = 0
                guideScrollXDir = 0
            }
        }
        return consumed
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        return true
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (loading) return super.dispatchGenericMotionEvent(event)
        val source = event.source
        val isJoystick = source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
                source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)
        if (screenStack.isNotEmpty()) {
            val menuConsumed = handleMenuMotion(event)
            if (menuConsumed) return true
            return super.dispatchGenericMotionEvent(event)
        }

        val deviceId = event.deviceId
        val mapping = portRouter.mappingFor(deviceId)
        val evaluator = portRouter.evaluatorFor(deviceId)

        // Activate on first meaningful stick motion so an analog-only controller is assigned
        // its own port rather than leaking input to port 0. Mirrors the launcher's first-press
        // activation step (InputDispatcher.maybeActivate), extended to pure analog motion that
        // never crosses a digital threshold.
        if (mapping != null && !portRouter.isActivated(deviceId) &&
            dev.cannoli.scorza.input.runtime.MotionActivation.shouldActivate(event)
        ) {
            portRouter.activate(deviceId, System.currentTimeMillis())
        }
        val port = portRouter.portFor(deviceId) ?: return super.dispatchGenericMotionEvent(event)

        // Track whether any axis binding actually matched. If nothing did (e.g. the active
        // mapping uses Button(19/20/21/22) for D-pad and the device emits HAT axes 15/16),
        // we hand the event off to super so ViewRootImpl's SyntheticJoystickHandler can
        // generate KEYCODE_DPAD_* KeyEvents from the unbound HAT motion. Those reach
        // dispatchKeyEvent and fire the Button bindings.
        //
        // Only Pressed/Released count as "axis bound"; AnalogChanged deltas from a bound
        // analog stick must NOT block the framework's HAT-to-keycode synthesis on a device
        // that also emits unbound HAT axes in the same MotionEvent (bound-stick + unbound-HAT
        // corner case).
        var axisBoundDeltas = false
        if (evaluator != null) {
            val axisValues = collectMotionAxes(mapping, event)
            val deltas = evaluator.evaluateAxis(axisValues)
            axisBoundDeltas = dev.cannoli.scorza.input.runtime.MotionDeltas.anyDigitalChange(deltas)
            pushPortMask(port)
        }

        val leftTrigger = maxOf(
            mappingTriggerValue(mapping, dev.cannoli.scorza.input.CanonicalButton.BTN_L2, event) ?: 0f,
            event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f),
            event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE).coerceIn(0f, 1f),
        )
        val rightTrigger = maxOf(
            mappingTriggerValue(mapping, dev.cannoli.scorza.input.CanonicalButton.BTN_R2, event) ?: 0f,
            event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f),
            event.getAxisValue(android.view.MotionEvent.AXIS_GAS).coerceIn(0f, 1f),
        )
        syncSyntheticTrigger(event.deviceId, port, KeyEvent.KEYCODE_BUTTON_L2, leftTrigger, triggerL2HeldDevices)
        syncSyntheticTrigger(event.deviceId, port, KeyEvent.KEYCODE_BUTTON_R2, rightTrigger, triggerR2HeldDevices)

        val stickX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val lStickX = mostActiveStick(mappingStickValue(mapping, dev.cannoli.scorza.input.AnalogRole.LEFT_STICK_X, event), stickX)
        val lStickY = mostActiveStick(mappingStickValue(mapping, dev.cannoli.scorza.input.AnalogRole.LEFT_STICK_Y, event), stickY)
        runner.setAnalog(port, 0, (lStickX * 32767).toInt().coerceIn(-32768, 32767),
            (lStickY * 32767).toInt().coerceIn(-32768, 32767))
        val rStickX = mostActiveStick(
            mappingStickValue(mapping, dev.cannoli.scorza.input.AnalogRole.RIGHT_STICK_X, event),
            event.getAxisValue(android.view.MotionEvent.AXIS_Z),
        )
        val rStickY = mostActiveStick(
            mappingStickValue(mapping, dev.cannoli.scorza.input.AnalogRole.RIGHT_STICK_Y, event),
            event.getAxisValue(android.view.MotionEvent.AXIS_RZ),
        )
        runner.setAnalog(port, 1, (rStickX * 32767).toInt().coerceIn(-32768, 32767),
            (rStickY * 32767).toInt().coerceIn(-32768, 32767))
        if (axisBoundDeltas) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun evaluatorForPort(port: Int): dev.cannoli.scorza.input.runtime.PortEvaluator? {
        val snap = portRouter.snapshotEntries().firstOrNull { it.port == port } ?: return null
        return portRouter.evaluatorFor(snap.androidDeviceId)
    }

    private fun pushPortMask(port: Int) {
        val eval = evaluatorForPort(port) ?: run {
            runner.setInput(port, 0)
            return
        }
        val remap = activeInputRemap
        var mask = 0
        for (cb in eval.currentlyPressed()) {
            mask = mask or dev.cannoli.scorza.input.runtime.CanonicalRetroMap.effectiveTarget(cb, remap)
        }
        runner.setInput(port, mask)
    }

    private fun collectMotionAxes(
        mapping: dev.cannoli.scorza.input.DeviceMapping?,
        event: android.view.MotionEvent,
    ): Map<Int, Float> {
        val axes = mutableSetOf<Int>()
        if (mapping != null) {
            for ((_, bindings) in mapping.bindings) {
                for (binding in bindings) {
                    when (binding) {
                        is dev.cannoli.scorza.input.InputBinding.Axis -> axes.add(binding.axis)
                        is dev.cannoli.scorza.input.InputBinding.Hat -> axes.add(binding.axis)
                        is dev.cannoli.scorza.input.InputBinding.Button -> Unit
                    }
                }
            }
        }
        return axes.associateWith { event.getAxisValue(it) }
    }

    private fun mostActiveStick(mapping: Float?, fallback: Float): Float {
        if (mapping == null) return fallback
        return if (kotlin.math.abs(mapping) >= kotlin.math.abs(fallback)) mapping else fallback
    }

    private fun mappingTriggerValue(
        mapping: dev.cannoli.scorza.input.DeviceMapping?,
        canonical: dev.cannoli.scorza.input.CanonicalButton,
        event: android.view.MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.get(canonical)
            ?.firstNotNullOfOrNull { it as? dev.cannoli.scorza.input.InputBinding.Axis }
            ?.takeIf { it.analogRole == dev.cannoli.scorza.input.AnalogRole.DIGITAL_BUTTON }
            ?: return null
        return axisBinding.normalize(event.getAxisValue(axisBinding.axis))
    }

    private fun mappingStickValue(
        mapping: dev.cannoli.scorza.input.DeviceMapping?,
        role: dev.cannoli.scorza.input.AnalogRole,
        event: android.view.MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.values
            ?.flatten()
            ?.firstNotNullOfOrNull {
                (it as? dev.cannoli.scorza.input.InputBinding.Axis)?.takeIf { axis -> axis.analogRole == role }
            }
            ?: return null
        val raw = event.getAxisValue(axisBinding.axis)
        val span = axisBinding.activeMax - axisBinding.restingValue
        if (span == 0f) return 0f
        val ratio = (raw - axisBinding.restingValue) / span
        val signed = (ratio * 2f - 1f).coerceIn(-1f, 1f)
        return if (axisBinding.invert) -signed else signed
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return super.dispatchKeyEvent(event)
        }
        val source = event.source
        val isGamepad = source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ctx = if (currentScreen != null) "libretro/igm" else "libretro/game"
            dev.cannoli.scorza.util.InputLog.write(
                "[$ctx dispatch] keyCode=${event.keyCode} source=0x${source.toString(16)} isGamepad=$isGamepad"
            )
        }
        if (isGamepad || event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount > 0) {
                val cs = currentScreen
                if (cs is IGMScreen.Guide || (cs is IGMScreen.Shortcuts && !cs.listening)) return true
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val onKeyDownCtx = if (currentScreen != null) "libretro/igm" else "libretro/game"
        dev.cannoli.scorza.util.InputLog.write("[$onKeyDownCtx onKeyDown] keyCode=$keyCode")
        if (missingBios.isNotEmpty()) {
            if (resolveNavButton(keyCode, event.deviceId) == "btn_east") finish()
            return true
        }
        if (loading) return true
        if (isSystemMediaKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (currentScreen == null) return handleGameplayInput(keyCode, event)
        // All IGM screens route through the dispatcher. Buttons and Shortcuts override the
        // ScreenInputHandler.onRawKeyDown hook to capture raw deviceId/repeatCount; others
        // dispatch via canonical events into the per-screen handler returned by igmHandlerFor.
        return inputDispatcher.handleKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (loading) return true
        if (isSystemMediaKey(keyCode)) return super.onKeyUp(keyCode, event)
        if (screenStack.isNotEmpty()) {
            val cs = currentScreen
            if (cs is IGMScreen.Guide) {
                when (resolveNavButton(keyCode, event.deviceId)) {
                    "btn_up", "btn_down" -> guideScrollDir = 0
                    "btn_left", "btn_right" -> guideScrollXDir = 0
                }
            }
            if (cs is IGMScreen.Info) {
                when (resolveNavButton(keyCode, event.deviceId)) {
                    "btn_up", "btn_down" -> infoScrollDir = 0
                }
            }
            bindingController.keyUp(keyCode)
            // Route through the dispatcher so the evaluator releases the canonical state.
            // Without this the next ACTION_DOWN sees the canonical already asserted and produces
            // no Pressed delta -- subsequent D-pad presses appear to do nothing.
            if (::inputDispatcher.isInitialized) inputDispatcher.handleKeyEvent(event)
            return true
        }
        val port = portRouter.portFor(event.deviceId) ?: 0
        val portKeys = portPressedKeys[port]
        portKeys.remove(keyCode)
        portConsumedKeys[port].remove(keyCode)

        if (holdingFf) {
            val holdChord = shortcuts[ShortcutAction.HOLD_FF]
            if (holdChord != null && !portKeys.containsAll(holdChord)) {
                holdingFf = false
                setFastForward(false)
            }
        }
        checkPendingHoldRelease(port)

        // The evaluator's Button asserter for this keycode releases here; if an Axis
        // asserter is still tracking the canonical, BTN_* stays in currentlyPressed via
        // that source and the libretro mask doesn't drop until the axis comes back to rest.
        val evaluator = evaluatorForPort(port) ?: return super.onKeyUp(keyCode, event)
        if (!evaluator.keyCodeIsBound(keyCode)) return super.onKeyUp(keyCode, event)
        evaluator.evaluateKeyUp(keyCode)
        pushPortMask(port)
        return true
    }

    private fun isSystemMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
        else -> false
    }

    private fun resolveNavButton(keyCode: Int, deviceId: Int): String? {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return "btn_east"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return "btn_south"
        }
        val mapping = portRouter.mappingFor(deviceId) ?: activeMappingHolder.active.value
        val canonical = mapping?.bindings?.entries?.firstOrNull { (_, bindings) ->
            bindings.any { it is dev.cannoli.scorza.input.InputBinding.Button && it.keyCode == keyCode }
        }?.key
        val pref = canonical?.let { canonicalToNavName(it) }
            ?: NAV_FALLBACK_KEY_MAP[keyCode]
            ?: return null
        return if (confirmButton == dev.cannoli.ui.ConfirmButton.EAST) {
            when (pref) {
                "btn_south" -> "btn_east"
                "btn_east" -> "btn_south"
                else -> pref
            }
        } else pref
    }

    private fun canonicalToNavName(c: dev.cannoli.scorza.input.CanonicalButton): String = when (c) {
        dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH -> "btn_south"
        dev.cannoli.scorza.input.CanonicalButton.BTN_EAST -> "btn_east"
        dev.cannoli.scorza.input.CanonicalButton.BTN_WEST -> "btn_west"
        dev.cannoli.scorza.input.CanonicalButton.BTN_NORTH -> "btn_north"
        dev.cannoli.scorza.input.CanonicalButton.BTN_UP -> "btn_up"
        dev.cannoli.scorza.input.CanonicalButton.BTN_DOWN -> "btn_down"
        dev.cannoli.scorza.input.CanonicalButton.BTN_LEFT -> "btn_left"
        dev.cannoli.scorza.input.CanonicalButton.BTN_RIGHT -> "btn_right"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L -> "btn_l"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R -> "btn_r"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L2 -> "btn_l2"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R2 -> "btn_r2"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L3 -> "btn_l3"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R3 -> "btn_r3"
        dev.cannoli.scorza.input.CanonicalButton.BTN_START -> "btn_start"
        dev.cannoli.scorza.input.CanonicalButton.BTN_SELECT -> "btn_select"
        dev.cannoli.scorza.input.CanonicalButton.BTN_MENU -> "btn_menu"
    }

    /**
     * Wires the shared InputDispatcher's canonical-event callbacks for IGM dispatch. The IGM has
     * no dialog precedence layer, so the helper is called with dialogHandler = null. Every IGM
     * screen registers a handler via ScreenInput in setContent (see igmHandlerFor); the registry's
     * top is always the active IGM screen's handler.
     */
    private fun wireDispatcherForIGM() {
        inputDispatcher.wireToRegistry(
            dialogHandler = null,
        )
    }

    /**
     * Returns a ScreenInputHandler for [screen] that delegates each canonical event to the
     * existing handle*Input function. Buttons and Shortcuts are intentionally not handled here:
     * they need raw deviceId/repeatCount and stay on the legacy onKeyDown switch.
     */
    private fun igmHandlerFor(screen: IGMScreen): dev.cannoli.scorza.input.ScreenInputHandler? = when (screen) {
        is IGMScreen.Menu -> simpleIgmHandler { btn -> handleMenuInput(screen, btn) }
        is IGMScreen.Settings -> simpleIgmHandler { btn -> handleCategoryInput(screen, btn) }
        is IGMScreen.Video -> simpleIgmHandler { btn -> handleVideoInput(screen, btn) }
        is IGMScreen.Advanced -> simpleIgmHandler { btn -> handleAdvancedInput(screen, btn) }
        is IGMScreen.ShaderSettings -> simpleIgmHandler { btn -> handleShaderSettingsInput(screen, btn) }
        is IGMScreen.Emulator -> simpleIgmHandler { btn -> handleEmulatorInput(screen, btn) }
        is IGMScreen.EmulatorCategory -> simpleIgmHandler { btn -> handleEmulatorCategoryInput(screen, btn) }
        is IGMScreen.SavePrompt -> simpleIgmHandler { btn -> handleSavePromptInput(screen, btn) }
        is IGMScreen.Info -> simpleIgmHandler { btn ->
            when (btn) {
                "btn_east", "btn_south" -> { infoScrollDir = 0; pop(); true }
                "btn_up" -> { infoScrollDir = -1; true }
                "btn_down" -> { infoScrollDir = 1; true }
                else -> true
            }
        }
        is IGMScreen.Achievements -> simpleIgmHandler { btn -> handleAchievementsInput(screen, btn) }
        is IGMScreen.AchievementDetail -> simpleIgmHandler { btn -> handleAchievementDetailInput(screen, btn) }
        is IGMScreen.GuidePicker -> simpleIgmHandler { btn -> handleGuidePickerInput(screen, btn) }
        is IGMScreen.Guide -> simpleIgmHandler { btn -> handleGuideInput(screen, btn) }
        is IGMScreen.ReassignPlayers -> simpleIgmHandler { btn -> handleReassignPlayersInput(screen, btn) }
        is IGMScreen.Buttons -> object : dev.cannoli.scorza.input.ScreenInputHandler {
            // Canonical events for nav between rows (works for hat-only D-pads where no KeyEvent
            // arrives). When the screen is in capture mode (listeningCanonical != null) we
            // ignore canonical events; the raw hook captures the keycode for binding.
            override fun onUp() { handleButtonsInput(screen, 0, 0, 0, "btn_up") }
            override fun onDown() { handleButtonsInput(screen, 0, 0, 0, "btn_down") }
            override fun onLeft() { handleButtonsInput(screen, 0, 0, 0, "btn_left") }
            override fun onRight() { handleButtonsInput(screen, 0, 0, 0, "btn_right") }
            override fun onConfirm() { handleButtonsInput(screen, 0, 0, 0, "btn_south") }
            override fun onBack() { handleButtonsInput(screen, 0, 0, 0, "btn_east") }
            override fun onNorth() { handleButtonsInput(screen, 0, 0, 0, "btn_north") }
            override fun onWest() { handleButtonsInput(screen, 0, 0, 0, "btn_west") }
            override fun onRawKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                val button = resolveNavButton(keyCode, event.deviceId)
                return handleButtonsInput(screen, keyCode, event.deviceId, event.repeatCount, button)
            }
        }
        is IGMScreen.Shortcuts -> object : dev.cannoli.scorza.input.ScreenInputHandler {
            // Canonical events for nav (between rows + L/R cycle on the source row). When
            // listening for a chord, the raw hook records keys.
            override fun onUp() { handleShortcutsInput(screen, 0, "btn_up") }
            override fun onDown() { handleShortcutsInput(screen, 0, "btn_down") }
            override fun onLeft() { handleShortcutsInput(screen, 0, "btn_left") }
            override fun onRight() { handleShortcutsInput(screen, 0, "btn_right") }
            override fun onConfirm() { handleShortcutsInput(screen, 0, "btn_south") }
            override fun onBack() { handleShortcutsInput(screen, 0, "btn_east") }
            override fun onNorth() { handleShortcutsInput(screen, 0, "btn_north") }
            override fun onRawKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                val button = resolveNavButton(keyCode, event.deviceId)
                return handleShortcutsInput(screen, keyCode, button)
            }
        }
    }

    private fun simpleIgmHandler(onButton: (String) -> Boolean): dev.cannoli.scorza.input.ScreenInputHandler =
        object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onUp() { onButton("btn_up") }
            override fun onDown() { onButton("btn_down") }
            override fun onLeft() { onButton("btn_left") }
            override fun onRight() { onButton("btn_right") }
            override fun onConfirm() { onButton("btn_south") }
            override fun onBack() { onButton("btn_east") }
            override fun onStart() { onButton("btn_start") }
            override fun onSelect() { onButton("btn_select") }
            override fun onWest() { onButton("btn_west") }
            override fun onNorth() { onButton("btn_north") }
            override fun onL1() { onButton("btn_l") }
            override fun onR1() { onButton("btn_r") }
            override fun onL2() { onButton("btn_l2") }
            override fun onR2() { onButton("btn_r2") }
            override fun onL3() { onButton("btn_l3") }
            override fun onR3() { onButton("btn_r3") }
            override fun onMenu() { onButton("btn_menu") }
        }

    private fun syncSyntheticTrigger(
        deviceId: Int,
        port: Int,
        keyCode: Int,
        value: Float,
        held: MutableSet<Int>,
    ) {
        val wasHeld = deviceId in held
        if (value > TRIGGER_PRESS_THRESHOLD && !wasHeld) {
            held.add(deviceId)
            val portKeys = portPressedKeys[port]
            if (portKeys.add(keyCode)) checkShortcuts(port)
        } else if (value < TRIGGER_RELEASE_THRESHOLD && wasHeld) {
            held.remove(deviceId)
            val portKeys = portPressedKeys[port]
            portKeys.remove(keyCode)
            portConsumedKeys[port].remove(keyCode)
            if (holdingFf) {
                val holdChord = shortcuts[ShortcutAction.HOLD_FF]
                if (holdChord != null && !portKeys.containsAll(holdChord)) {
                    holdingFf = false
                    setFastForward(false)
                }
            }
            checkPendingHoldRelease(port)
        }
    }

    private fun handleGameplayInput(keyCode: Int, event: KeyEvent): Boolean {
        val port = portRouter.portFor(event.deviceId) ?: 0
        val evaluator = evaluatorForPort(port)
        val mapping = portRouter.mappingForPort(port)
        val mapsToCanonical = evaluator?.keyCodeIsBound(keyCode) == true
        val opensMenu = mapping?.bindings?.get(dev.cannoli.scorza.input.CanonicalButton.BTN_MENU)
            ?.any { it is dev.cannoli.scorza.input.InputBinding.Button && it.keyCode == keyCode } == true
        if (opensMenu) { openMenu(); return true }
        // Don't early-return on synthetic-trigger held: portPressedKeys.add already dedupes for
        // chord detection, and on devices whose trigger axis rests at 0 the mapping importer
        // normalizes it to 0.5 -- past the press threshold but never below the release
        // threshold, so triggerR2HeldDevices stays populated forever and a real button press
        // would silently no-op.
        val portKeys = portPressedKeys[port]
        val isNewPress = portKeys.add(keyCode)
        if (isNewPress) checkShortcuts(port)
        if (keyCode in portConsumedKeys[port]) return true
        if (evaluator == null || !mapsToCanonical) return super.onKeyDown(keyCode, event)
        evaluator.evaluateKeyDown(keyCode, event.repeatCount > 0)
        pushPortMask(port)
        return true
    }

    private fun checkShortcuts(port: Int) {
        val portKeys = portPressedKeys[port]
        val consumed = portConsumedKeys[port]
        for ((action, chord) in shortcuts) {
            if (chord.isEmpty() || !portKeys.containsAll(chord)) continue
            if (chord.any { it in consumed }) continue
            when (action) {
                ShortcutAction.SAVE_STATE -> {
                    if (stateBasePath.isNotEmpty()) {
                        slotManager.saveState(runner, currentSlot)
                        showOsd(getString(R.string.osd_state_saved, currentSlot.label), OsdPosition.BottomCenter)
                    }
                }
                ShortcutAction.LOAD_STATE -> {
                    if (stateBasePath.isNotEmpty() && slotManager.stateExists(currentSlot)) {
                        slotManager.loadState(runner, currentSlot)
                        sessionLog.log("RA state load (shortcut): slot=${currentSlot.label}")
                        showOsd(getString(R.string.osd_state_loaded, currentSlot.label), OsdPosition.BottomCenter)
                    }
                }
                ShortcutAction.RESET_GAME -> {
                    if (stateBasePath.isNotEmpty()) {
                        slotManager.cacheForUndoLoad(runner)
                        undoType = UndoType.RESET
                        undoSlot = null
                        startUndoTimer(30_000)
                    }
                    runner.reset()
                    showOsd(getString(R.string.osd_reset), OsdPosition.BottomCenter)
                }
                ShortcutAction.SAVE_AND_QUIT -> {
                    performSaveAndQuit()
                }
                ShortcutAction.SAVE_AND_QUIT_HOLD -> {
                    if (pendingHoldAction == action) continue
                    schedulePendingHold(action, port, chord)
                }
                ShortcutAction.CYCLE_SCALING -> {
                    val modes = ScalingMode.entries
                    scalingMode = modes[(scalingMode.ordinal + 1) % modes.size]
                    renderer.scalingMode = scalingMode
                    showOsd(getString(R.string.osd_scaling, scalingLabel()), OsdPosition.BottomCenter)
                }
                ShortcutAction.CYCLE_EFFECT -> {
                    cycleShader(1)
                    val label = if (shaderPreset.isEmpty()) "Off" else File(shaderPreset).nameWithoutExtension
                    showOsd(getString(R.string.osd_shader, label), OsdPosition.BottomCenter)
                }
                ShortcutAction.TOGGLE_FF -> {
                    setFastForward(!fastForwarding)
                }
                ShortcutAction.HOLD_FF -> {
                    if (holdingFf) continue
                    holdingFf = true; setFastForward(true)
                }
                ShortcutAction.OPEN_MENU -> {
                    openMenu()
                }
                ShortcutAction.TOGGLE_SHOW_FPS -> {
                    showFps = !showFps
                }
                ShortcutAction.OPEN_GUIDE -> {
                    val guides = guideManager.findGuides()
                    if (guides.isNotEmpty()) {
                        stopVsyncPacer()
                        renderer.paused = true
                        syncGlThread()
                        runner.pauseAudio()
                        startRaPausedIdle()
                        screenStack.clear()
                        guideFiles = guides
                        if (guides.size == 1) openGuide(guides[0])
                        else push(IGMScreen.GuidePicker())
                    }
                }
            }
            consumed.addAll(chord)
            val evaluator = evaluatorForPort(port)
            if (evaluator != null) {
                for (key in chord) evaluator.evaluateKeyUp(key)
            }
            pushPortMask(port)
            break
        }
    }

    // --- Menu screen ---

    private fun openMenu() {
        sessionLog.log("openMenu: enter")
        cancelPendingHold()
        raManager?.idle()
        sessionLog.log("openMenu: raIdle")
        if (!raHasAchievements) {
            raManager?.let { ra -> raHasAchievements = ra.isLoggedIn && ra.getAchievements().isNotEmpty() }
        }
        sessionLog.log("openMenu: raAch")
        guideFiles = guideManager.findGuides()
        sessionLog.log("openMenu: guides=${guideFiles.size}")
        screenStack.clear()
        push(IGMScreen.Menu())
        sessionLog.log("openMenu: stack")
        stopVsyncPacer()
        sessionLog.log("openMenu: vsync stopped")
        renderer.paused = true
        syncGlThread()
        sessionLog.log("openMenu: glSynced")
        runner.pauseAudio()
        sessionLog.log("openMenu: audioPaused")
        startRaPausedIdle()
        sessionLog.log("openMenu: raPausedIdle")
        refreshSlotInfo()
        sessionLog.log("openMenu: slotInfo")
        refreshDiskInfo()
        sessionLog.log("openMenu: diskInfo")
        setKeepScreenOnForGameplay(false)
        sessionLog.log("openMenu: ready")
    }

    private fun closeAll() {
        screenStack.clear()
        if (::stickAutoRepeat.isInitialized) stickAutoRepeat.stop()
        for (set in portPressedKeys) set.clear()
        triggerL2HeldDevices.clear()
        triggerR2HeldDevices.clear()
        for (set in portConsumedKeys) set.clear()
        if (holdingFf) {
            holdingFf = false
            setFastForward(false)
        }
        cancelPendingHold()
        for (p in 0 until LibretroRunner.MAX_PORTS) runner.setInput(p, 0)
        stopRaPausedIdle()
        renderer.paused = false
        runner.resumeAudio()
        startVsyncPacer()
        setKeepScreenOnForGameplay(true)
    }

    private fun setKeepScreenOnForGameplay(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshSlotInfo() {
        val slot = currentSlot
        slotExists = slotManager.stateExists(slot)
        sessionLog.log("refreshSlotInfo: slot=${slot.label} exists=$slotExists")
        slotThumbnail = slotManager.loadThumbnail(slot)
        sessionLog.log("refreshSlotInfo: thumb=${slotThumbnail != null}")
        slotOccupied = slotManager.slots.map { slotManager.stateExists(it) }
        sessionLog.log("refreshSlotInfo: occupied=${slotOccupied.count { it }}/${slotOccupied.size}")
    }

    private fun cycleSlot(direction: Int) {
        val count = slotManager.slots.size
        selectedSlotIndex = ((selectedSlotIndex + direction) + count) % count
        refreshSlotInfo()
    }

    private fun cycleDisc(direction: Int) {
        val newIndex = ((currentDiskIndex + direction) + diskCount) % diskCount
        if (newIndex == currentDiskIndex) return
        val ok = runOnGlThread(200L, false) { runner.setDiskIndex(newIndex) }
        if (ok) {
            currentDiskIndex = newIndex
            showOsd(getString(R.string.osd_switched_to, diskLabel(currentDiskIndex)), OsdPosition.TopCenter)
        }
    }

    private fun handleMenuInput(screen: IGMScreen.Menu, button: String?): Boolean {
        if (screen.confirmDeleteSlot) {
            return when (button) {
                "btn_north" -> {
                    slotManager.deleteState(currentSlot)
                    refreshSlotInfo()
                    showOsd(getString(R.string.osd_state_deleted, currentSlot.label), OsdPosition.BottomCenter)
                    replaceTop(screen.copy(confirmDeleteSlot = false))
                    true
                }
                "btn_east" -> {
                    replaceTop(screen.copy(confirmDeleteSlot = false))
                    true
                }
                else -> true
            }
        }

        val menu = menuOptions()
        val options = menu.options
        val onSlotRow = screen.selectedIndex == menu.saveStateIndex || screen.selectedIndex == menu.loadStateIndex
        val onDiscRow = screen.selectedIndex == menu.switchDiscIndex
        return when (button) {
            "btn_up" -> {
                val idx = ((screen.selectedIndex - 1) + options.size) % options.size
                replaceTop(screen.copy(selectedIndex = idx))
                if (idx == menu.saveStateIndex || idx == menu.loadStateIndex) refreshSlotInfo()
                true
            }
            "btn_down" -> {
                val idx = (screen.selectedIndex + 1) % options.size
                replaceTop(screen.copy(selectedIndex = idx))
                if (idx == menu.saveStateIndex || idx == menu.loadStateIndex) refreshSlotInfo()
                true
            }
            "btn_left" -> {
                when {
                    onSlotRow -> cycleSlot(-1)
                    onDiscRow -> cycleDisc(-1)
                }
                true
            }
            "btn_right" -> {
                when {
                    onSlotRow -> cycleSlot(1)
                    onDiscRow -> cycleDisc(1)
                }
                true
            }
            "btn_south" -> {
                handleMenuAction(menu, screen.selectedIndex); true
            }
            "btn_north" -> { if (undoType != null) performUndo(); true }
            "btn_west" -> {
                if (onSlotRow && slotExists) {
                    replaceTop(screen.copy(confirmDeleteSlot = true))
                }
                true
            }
            "btn_east" -> { closeAll(); true }
            else -> true
        }
    }

    private fun handleMenuAction(menu: InGameMenuOptions, index: Int) {
        when (menu.actionAt(index)) {
            IgmMenuAction.RESUME -> closeAll()
            IgmMenuAction.SAVE_STATE -> {
                if (stateBasePath.isNotEmpty()) {
                    val slot = currentSlot
                    if (slot.index != 0) {
                        slotManager.cacheForUndoSave(slot)
                        undoType = UndoType.SAVE
                        undoSlot = slot
                        startUndoTimer()
                    }
                    slotManager.saveState(runner, slot)
                    refreshSlotInfo()
                    showOsd(getString(R.string.osd_state_saved, slot.label), OsdPosition.BottomCenter)
                }
                closeAll()
            }
            IgmMenuAction.LOAD_STATE -> {
                if (stateBasePath.isNotEmpty() && slotManager.stateExists(currentSlot)) {
                    val slot = currentSlot
                    slotManager.cacheForUndoLoad(runner)
                    undoType = UndoType.LOAD
                    undoSlot = null
                    startUndoTimer()
                    slotManager.loadState(runner, slot)
                    sessionLog.log("RA state load (IGM): slot=${slot.label}")
                    showOsd(getString(R.string.osd_state_loaded, slot.label), OsdPosition.BottomCenter)
                }
                closeAll()
            }
            IgmMenuAction.GUIDE -> {
                if (guideFiles.size == 1) {
                    openGuide(guideFiles[0])
                } else {
                    push(IGMScreen.GuidePicker())
                }
            }
            IgmMenuAction.SETTINGS -> {
                coreOptions = loadVisibleCoreOptions()
                refreshShaderParams()
                frontendSnapshot = buildCurrentSettings()
                shaderParamsDirty = false
                push(IGMScreen.Settings())
            }
            IgmMenuAction.REASSIGN -> {
                push(IGMScreen.ReassignPlayers())
            }
            IgmMenuAction.RESET -> {
                if (stateBasePath.isNotEmpty()) {
                    slotManager.cacheForUndoLoad(runner)
                    undoType = UndoType.RESET
                    undoSlot = null
                    startUndoTimer(30_000)
                }
                runner.reset()
                sessionLog.log("RA reset (IGM game reset)")
                raManager?.reset()
                showOsd(getString(R.string.osd_reset), OsdPosition.BottomCenter)
                closeAll()
            }
            IgmMenuAction.ACHIEVEMENTS -> {
                val ra = raManager ?: return
                val pending = ra.pendingSyncIds
                val local = ra.localUnlocks
                val achievements = ra.getAchievements().map {
                    val ach = when {
                        it.id in pending -> it.copy(unlocked = true, pendingSync = true)
                        it.id in local -> it.copy(unlocked = true)
                        else -> it
                    }
                    ach.toAchievementInfo()
                }
                push(IGMScreen.Achievements(achievements = achievements, status = ra.getStatus()))
            }
            IgmMenuAction.QUIT -> {
                if (alwaysSaveOnQuit && stateBasePath.isNotEmpty()) {
                    try {
                        slotManager.saveState(runner, slotManager.slots[0])
                    } catch (t: Throwable) {
                        sessionLog.log("alwaysSaveOnQuit failed: ${t.message}")
                    }
                }
                quit()
            }
            IgmMenuAction.SWITCH_DISC, null -> {}
        }
    }

    // --- Settings category screen ---

    private fun handleCategoryInput(screen: IGMScreen.Settings, button: String?): Boolean {
        val count = IGMSettings.CATEGORIES.size
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_south" -> {
                when (screen.selectedIndex) {
                    IGMSettings.VIDEO -> push(IGMScreen.Video())
                    IGMSettings.EMULATOR -> {
                        coreOptions = loadVisibleCoreOptions()
                        coreCategories = runner.getCoreCategories()
                        push(IGMScreen.Emulator())
                    }
                    IGMSettings.BUTTONS -> {
                        push(IGMScreen.Buttons())
                    }
                    IGMSettings.SHORTCUTS -> push(IGMScreen.Shortcuts())
                    IGMSettings.ADVANCED -> push(IGMScreen.Advanced())
                    IGMSettings.INFO -> push(IGMScreen.Info())
                }
                true
            }
            "btn_east" -> {
                val snap = frontendSnapshot
                if (snap != null && (shaderParamsDirty || !buildCurrentSettings().frontendEquals(snap))) {
                    push(IGMScreen.SavePrompt())
                } else {
                    pop()
                }
                true
            }
            else -> true
        }
    }

    // --- Frontend ---

    private fun scalingLabel() = when (scalingMode) {
        ScalingMode.CORE_REPORTED -> getString(R.string.scaling_core_reported)
        ScalingMode.INTEGER -> getString(R.string.scaling_integer)
        ScalingMode.INTEGER_OVERSCALE -> getString(R.string.scaling_integer_overscale)
        ScalingMode.ASPECT_SCREEN -> getString(R.string.scaling_aspect_screen)
        ScalingMode.FULLSCREEN -> getString(R.string.scaling_fullscreen)
    }

    private fun sharpnessLabel() = when (sharpness) {
        Sharpness.SHARP -> "Sharp"
        Sharpness.SOFT -> "Soft"
    }

    private fun overlayLabel() = if (overlay.isEmpty()) "None" else File(overlay).nameWithoutExtension

    private fun resolveOverlayPath(): String? =
        if (overlay.isEmpty()) null else File(dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).overlaysFor(platformTag), overlay).absolutePath

    private fun scanOverlayImages() {
        val dir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).overlaysFor(platformTag)
        val exts = setOf("png", "jpg", "jpeg")
        overlayImages = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    private fun copyBundledShaders() {
        val destDir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).shadersDir
        val versionFile = File(destDir, ".bundled_version")
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toString()
        } catch (_: Exception) { "" }
        if (versionFile.exists() && versionFile.readText().trim() == currentVersion) return
        destDir.mkdirs()
        purgeSlangShaders(destDir)
        copyAssetDir("shaders", destDir)
        versionFile.writeText(currentVersion)
    }

    private fun purgeSlangShaders(root: File) {
        if (!root.isDirectory) return
        val slangExts = setOf("slang", "slangp")
        root.walkBottomUp().forEach { f ->
            when {
                f.isFile && f.extension.lowercase(java.util.Locale.ROOT) in slangExts -> f.delete()
                f.isDirectory && f != root && f.listFiles()?.isEmpty() == true -> f.delete()
            }
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = try { assets.list(assetPath) } catch (_: Exception) { return }
        if (entries.isNullOrEmpty()) return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = try { assets.list(src) } catch (_: Exception) { null }
            if (!subEntries.isNullOrEmpty()) {
                copyAssetDir(src, dest)
            } else {
                dest.parentFile?.mkdirs()
                assets.open(src).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun scanShaderPresets() {
        val dir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).shadersDir
        val exts = setOf("glslp")
        shaderPresets = dir.walk()
            .filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            .map { it.relativeTo(dir).path }
            .sorted()
            .toList()
        sessionLog.log("scanShaderPresets: ${shaderPresets.size} presets found in ${dir.absolutePath}")
    }

    private fun resolveShaderPresetPath(): String? =
        if (shaderPreset.isEmpty()) null
        else File(dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).shadersDir, shaderPreset).absolutePath

    private fun refreshShaderParams() {
        val path = resolveShaderPresetPath()
        if (path.isNullOrEmpty()) { shaderParams = emptyList(); return }
        val preset = PresetParser.parse(File(path))
        if (preset == null) { shaderParams = emptyList(); return }
        val existing = shaderParams.associate { it.id to it.value }
        shaderParams = preset.parameters.values.map { p ->
            val value = existing[p.id] ?: p.default
            ShaderParamItem(p.id, p.description, value, p.min, p.max, p.step)
        }
    }

    private fun applySavedShaderParams(saved: Map<String, Float>) {
        if (saved.isEmpty()) return
        shaderParams = shaderParams.map { p ->
            val v = saved[p.id] ?: return@map p
            p.copy(value = v)
        }
    }

    private fun pushShaderParamsToRenderer() {
        for (p in shaderParams) {
            renderer.setShaderParameter(p.id, p.value)
        }
    }

    private fun cycleOverlay(direction: Int) {
        if (overlayImages.isEmpty()) { overlay = ""; return }
        val currentIndex = overlayImages.indexOf(overlay)
        val newIndex = if (currentIndex == -1) {
            if (direction > 0) 0 else overlayImages.lastIndex
        } else {
            val raw = currentIndex + direction
            if (raw < 0 || raw >= overlayImages.size) -1 else raw
        }
        overlay = if (newIndex < 0) "" else overlayImages[newIndex]
        renderer.overlayPath = resolveOverlayPath()
    }

    private fun handleVideoInput(screen: IGMScreen.Video, button: String?): Boolean {
        val hasParams = shaderParams.isNotEmpty()
        val count = buildSettingsItems().size
        if (count == 0) return true
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left", "btn_right" -> {
                val dir = if (button == "btn_right") 1 else -1
                cycleVideoValue(screen.selectedIndex, dir, hasParams)
                true
            }
            "btn_south" -> {
                val shaderSettingsIdx = if (hasParams) 3 else -1
                if (screen.selectedIndex == shaderSettingsIdx) push(IGMScreen.ShaderSettings())
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun cycleVideoValue(index: Int, direction: Int, hasParams: Boolean) {
        when (index) {
            0 -> {
                val modes = ScalingMode.entries
                scalingMode = modes[(scalingMode.ordinal + direction + modes.size) % modes.size]
                renderer.scalingMode = scalingMode
            }
            1 -> {
                val vals = Sharpness.entries
                sharpness = vals[(sharpness.ordinal + direction + vals.size) % vals.size]
                renderer.sharpness = sharpness
            }
            2 -> cycleShader(direction)
            3 -> if (!hasParams) cycleOverlay(direction)
            4 -> cycleOverlay(direction)
        }
    }

    private fun handleAdvancedInput(screen: IGMScreen.Advanced, button: String?): Boolean {
        val count = buildSettingsItems().size
        if (count == 0) return true
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left", "btn_right" -> {
                val dir = if (button == "btn_right") 1 else -1
                cycleAdvancedValue(screen.selectedIndex, dir)
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun occupiedPorts(): List<Int> = portRouter.snapshotEntries()
        .filter { it.port != null && !it.mapping.excludeFromGameplay }
        .mapNotNull { it.port }
        .sorted()

    private fun deviceTypeLabel(port: Int): String {
        val typeId = portDeviceTypes[port] ?: LibretroRunner.DEVICE_JOYPAD
        return controllerTypes.firstOrNull { it.id == typeId }?.desc ?: "Standard"
    }

    private fun cyclePortDeviceType(port: Int, direction: Int) {
        if (controllerTypes.isEmpty()) return
        val currentTypeId = portDeviceTypes[port] ?: LibretroRunner.DEVICE_JOYPAD
        val currentIdx = controllerTypes.indexOfFirst { it.id == currentTypeId }.coerceAtLeast(0)
        val newIdx = ((currentIdx + direction) + controllerTypes.size) % controllerTypes.size
        val ct = controllerTypes[newIdx]
        portDeviceTypes = portDeviceTypes.toMutableMap().also { it[port] = ct.id }
        if (port == 0) {
            controllerTypeIndex = newIdx
            applyForceAnalog(ct.id > 1)
        }
        runner.setControllerPortDevice(port, ct.id)
    }

    private fun cycleAdvancedValue(index: Int, direction: Int) {
        val portRows = if (controllerTypes.size > 1) {
            val ports = occupiedPorts()
            if (ports.size <= 1) listOf(0) else ports
        } else emptyList()
        if (index < portRows.size) {
            cyclePortDeviceType(portRows[index], direction)
            return
        }
        when (index - portRows.size) {
            0 -> cycleFfSpeed(direction)
            1 -> {
                showFpsBaseline = !showFpsBaseline
                showFps = showFpsBaseline
            }
            2 -> { debugHud = !debugHud; renderer.debugHud = debugHud }
        }
    }

    private fun applyForceAnalog(enable: Boolean) {
        val key = coreOptions.find {
            val k = it.key.lowercase()
            "analog" in k && ("force" in k || "auto" in k)
        }?.key ?: return
        val value = if (enable) "true" else "false"
        runner.setCoreOption(key, value)
        coreOptions = loadVisibleCoreOptions()
    }

    private fun cycleShader(direction: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShaderCycleMs < 250) return
        lastShaderCycleMs = now
        if (shaderPresets.isEmpty()) {
            screenEffect = ScreenEffect.NONE
            shaderPreset = ""
        } else {
            val currentIndex = if (screenEffect == ScreenEffect.NONE || shaderPreset.isEmpty()) -1
                else shaderPresets.indexOf(shaderPreset)
            val total = shaderPresets.size + 1
            val newIndex = ((currentIndex + 1 + direction) % total + total) % total - 1
            if (newIndex in 0 until shaderPresets.size) {
                screenEffect = ScreenEffect.SHADER
                shaderPreset = shaderPresets[newIndex]
            } else {
                screenEffect = ScreenEffect.NONE
                shaderPreset = ""
            }
        }
        shaderParamsDirty = true
        renderer.clearShaderParamOverrides()
        renderer.screenEffect = screenEffect
        renderer.shaderPresetPath = resolveShaderPresetPath()
        shaderParams = emptyList()
        refreshShaderParams()
    }

    private fun filteredAchievements(screen: IGMScreen.Achievements): List<AchievementInfo> = when (screen.filter) {
        1 -> screen.achievements.filter { it.unlocked }
        2 -> screen.achievements.filter { !it.unlocked }
        else -> screen.achievements
    }

    private fun handleAchievementsInput(screen: IGMScreen.Achievements, button: String?): Boolean {
        val filtered = filteredAchievements(screen)
        val count = filtered.size
        if (count == 0 && button != "btn_north" && button != "btn_west") return when (button) {
            "btn_east" -> { pop(); true }
            else -> true
        }
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_south" -> {
                var ach = filtered.getOrNull(screen.selectedIndex)
                if (ach != null) {
                    val ra = raManager
                    if (ra != null) {
                        if (ach.id in ra.pendingSyncIds) ach = ach.copy(unlocked = true, pendingSync = true)
                        else if (ach.id in ra.localUnlocks) ach = ach.copy(unlocked = true)
                    }
                    push(IGMScreen.AchievementDetail(achievement = ach, parentIndex = screen.selectedIndex))
                }
                true
            }
            "btn_west" -> {
                val newFilter = (screen.filter + 1) % 3
                replaceTop(screen.copy(filter = newFilter, selectedIndex = 0))
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun handleAchievementDetailInput(screen: IGMScreen.AchievementDetail, button: String?): Boolean {
        return when (button) {
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun openGuide(guide: GuideFile) {
        val saved = guideManager.loadSavedPosition(guide.file)
        guideScrollDir = 0
        guideScrollXDir = 0
        guidePageJump = 0
        guideScrollXPos = saved.scrollX
        guideInitialScrollX = saved.scrollX
        guidePageCount = if (guide.type == GuideType.PDF) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(guide.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                pfd.use { android.graphics.pdf.PdfRenderer(it).use { r -> r.pageCount } }
            } catch (_: Exception) { 1 }
        } else 0
        guideScrollPos = if (guide.type == GuideType.PDF) saved.scrollY else saved.position
        guideInitialScroll = guideScrollPos
        if (guide.type == GuideType.PDF) {
            push(IGMScreen.Guide(filePath = guide.file.absolutePath, page = saved.position.coerceIn(0, (guidePageCount - 1).coerceAtLeast(0)), textZoom = saved.zoom))
        } else {
            push(IGMScreen.Guide(filePath = guide.file.absolutePath, textZoom = saved.zoom))
        }
    }

    private fun handleGuidePickerInput(screen: IGMScreen.GuidePicker, button: String?): Boolean {
        val count = guideFiles.size
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_south" -> {
                guideFiles.getOrNull(screen.selectedIndex)?.let { openGuide(it) }
                true
            }
            "btn_east" -> {
                pop()
                if (screenStack.isEmpty()) closeAll()
                true
            }
            else -> true
        }
    }

    private fun handleGuideInput(screen: IGMScreen.Guide, button: String?): Boolean {
        val guide = guideFiles.firstOrNull { it.file.absolutePath == screen.filePath }
        val type = guide?.type ?: return true
        return when (button) {
            "btn_up" -> { guideScrollDir = -1; true }
            "btn_down" -> { guideScrollDir = 1; true }
            "btn_left" -> {
                if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir = -1
                true
            }
            "btn_right" -> {
                if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir = 1
                true
            }
            "btn_l" -> {
                if (type == GuideType.PDF) {
                    replaceTop(screen.copy(page = (screen.page - 1).coerceAtLeast(0)))
                } else {
                    guidePageJumpDir = -1; guidePageJump++
                }
                true
            }
            "btn_r" -> {
                if (type == GuideType.PDF) {
                    replaceTop(screen.copy(page = (screen.page + 1).coerceAtMost(guidePageCount - 1)))
                } else {
                    guidePageJumpDir = 1; guidePageJump++
                }
                true
            }
            "btn_north" -> {
                guideInitialScroll = guideScrollPos
                guideInitialScrollX = guideScrollXPos
                replaceTop(screen.copy(textZoom = if (screen.textZoom >= 3) 1 else screen.textZoom + 1))
                true
            }
            "btn_east" -> {
                val pos = if (type == GuideType.PDF) screen.page else guideScrollPos
                guideManager.save(guide.file, pos, guideScrollPos, guideScrollXPos, screen.textZoom)
                guideScrollDir = 0
                guideScrollXDir = 0
                pop()
                if (screenStack.isEmpty()) closeAll()
                true
            }
            else -> true
        }
    }

    private fun handleShaderSettingsInput(screen: IGMScreen.ShaderSettings, button: String?): Boolean {
        val count = shaderParams.size
        if (count == 0) return when (button) {
            "btn_east" -> { pop(); true }
            else -> true
        }
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left" -> { cycleShaderParam(screen.selectedIndex, -1); true }
            "btn_right" -> { cycleShaderParam(screen.selectedIndex, 1); true }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun cycleShaderParam(index: Int, direction: Int) {
        shaderParamsDirty = true
        val param = shaderParams.getOrNull(index) ?: return
        val newValue = cycleFloat(param.value, direction, param.min, param.max, param.step)
        shaderParams = shaderParams.toMutableList().also {
            it[index] = param.copy(value = newValue)
        }
        renderer.setShaderParameter(param.id, newValue)
    }

    private fun cycleFloat(current: Float, direction: Int, min: Float, max: Float, step: Float): Float {
        val next = current + direction * step
        return (Math.round(next / step) * step).coerceIn(min, max)
    }

    private fun cycleFfSpeed(direction: Int) {
        val idx = FF_SPEEDS.indexOf(maxFfSpeed).coerceAtLeast(0)
        maxFfSpeed = FF_SPEEDS[(idx + direction + FF_SPEEDS.size) % FF_SPEEDS.size]
        if (fastForwarding) renderer.fastForwardFrames = maxFfSpeed
    }

    // --- Emulator ---

    private fun emulatorMenuItems(): List<String> {
        if (coreOptions.isEmpty()) return listOf("No options available")
        val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
        if (usedCategories.isEmpty()) return emptyList()
        val items = usedCategories.map { it.desc }.toMutableList()
        val uncategorized = coreOptions.filter { it.category.isEmpty() }
        if (uncategorized.isNotEmpty()) items.add("Other")
        return items
    }

    private fun emulatorHasCategories(): Boolean =
        coreCategories.isNotEmpty() && coreOptions.any { it.category.isNotEmpty() }

    private fun handleEmulatorInput(screen: IGMScreen.Emulator, button: String?): Boolean {
        if (screen.showDescription) {
            return if (button == "btn_east" || button == "btn_south") {
                replaceTop(screen.copy(showDescription = false)); true
            } else true
        }
        if (coreOptions.isEmpty()) {
            return if (button == "btn_east") { pop(); true } else true
        }
        if (emulatorHasCategories()) {
            val items = emulatorMenuItems()
            val count = items.size
            return when (button) {
                "btn_up" -> {
                    replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
                }
                "btn_down" -> {
                    replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
                }
                "btn_south" -> {
                    val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
                    val cat = usedCategories.getOrNull(screen.selectedIndex)
                    push(IGMScreen.EmulatorCategory(categoryKey = cat?.key ?: "", categoryTitle = cat?.desc ?: ""))
                    true
                }
                "btn_east" -> { pop(); true }
                else -> true
            }
        }
        val count = coreOptions.size
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left" -> { cycleEmulatorValue(coreOptions, screen.selectedIndex, -1); true }
            "btn_right" -> { cycleEmulatorValue(coreOptions, screen.selectedIndex, 1); true }
            "btn_south" -> {
                val info = coreOptions.getOrNull(screen.selectedIndex)?.info
                if (!info.isNullOrEmpty()) replaceTop(screen.copy(showDescription = true))
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun handleEmulatorCategoryInput(screen: IGMScreen.EmulatorCategory, button: String?): Boolean {
        if (screen.showDescription) {
            return if (button == "btn_east" || button == "btn_south") {
                replaceTop(screen.copy(showDescription = false)); true
            } else true
        }
        val filtered = coreOptions.filter { it.category == screen.categoryKey }
        if (filtered.isEmpty()) {
            return if (button == "btn_east") { pop(); true } else true
        }
        val count = filtered.size
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left" -> { cycleEmulatorValue(filtered, screen.selectedIndex, -1); true }
            "btn_right" -> { cycleEmulatorValue(filtered, screen.selectedIndex, 1); true }
            "btn_south" -> {
                val info = filtered.getOrNull(screen.selectedIndex)?.info
                if (!info.isNullOrEmpty()) replaceTop(screen.copy(showDescription = true))
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun loadVisibleCoreOptions(): List<LibretroRunner.CoreOption> {
        val all = runner.getCoreOptions()
        if (!coreRequiresHwRender) return all
        return all.filterNot { isHwRenderGatedOption(it) }
    }

    private fun isHwRenderGatedOption(opt: LibretroRunner.CoreOption): Boolean {
        val haystack = (opt.key + " " + opt.desc).lowercase()
        return HW_RENDER_GATED_PATTERNS.any { it in haystack }
    }

    private fun cycleEmulatorValue(options: List<LibretroRunner.CoreOption>, index: Int, direction: Int) {
        val opt = options.getOrNull(index) ?: return
        if (opt.values.isEmpty()) return
        val curIdx = opt.values.indexOfFirst { it.value == opt.selected }.coerceAtLeast(0)
        val newVal = opt.values[(curIdx + direction + opt.values.size) % opt.values.size]
        runner.setCoreOption(opt.key, newVal.value)
        coreOptions = loadVisibleCoreOptions()
    }




    // --- Shortcuts ---

    private fun wireBindingController() {
        bindingController.onProgress = { keys, elapsedMs ->
            val cs = currentScreen
            if (cs is IGMScreen.Shortcuts) {
                replaceTop(cs.copy(heldKeys = keys, countdownMs = elapsedMs))
            }
        }
        bindingController.onCommit = { chord ->
            val cs = currentScreen
            if (cs is IGMScreen.Shortcuts) {
                // selectedIndex 0 is the source picker; actions start at 1.
                val action = ShortcutAction.entries.getOrNull(cs.selectedIndex - 1)
                if (action != null) {
                    val cleared = shortcuts.filterValues { it != chord }
                    shortcuts = cleared + (action to chord)
                    saveCurrentShortcuts()
                    replaceTop(cs.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
                }
            }
        }
        bindingController.onCancel = {
            val cs = currentScreen
            if (cs is IGMScreen.Shortcuts && cs.listening) {
                replaceTop(cs.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
            }
        }
    }

    private fun handleShortcutsInput(screen: IGMScreen.Shortcuts, rawKeyCode: Int, button: String?): Boolean {
        if (screen.listening) {
            bindingController.keyDown(rawKeyCode)
            return true
        }
        val count = ShortcutAction.entries.size + 1
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_left" -> {
                if (screen.selectedIndex == 0) cycleShortcutSource(-1)
                true
            }
            "btn_right" -> {
                if (screen.selectedIndex == 0) cycleShortcutSource(1)
                true
            }
            "btn_south" -> {
                if (screen.selectedIndex > 0) {
                    replaceTop(screen.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                    bindingController.startListening()
                }
                true
            }
            "btn_north" -> {
                if (screen.selectedIndex > 0) {
                    val action = ShortcutAction.entries[screen.selectedIndex - 1]
                    shortcuts = shortcuts + (action to emptySet())
                    saveCurrentShortcuts()
                }
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

    private fun cycleShortcutSource(direction: Int) {
        val sources = OverrideSource.entries
        shortcutSource = sources[(shortcutSource.ordinal + direction + sources.size) % sources.size]
        overrideManager.saveShortcutSource(shortcutSource)
        shortcuts = overrideManager.loadShortcutsForSource(shortcutSource)
    }

    private fun saveCurrentShortcuts() {
        overrideManager.saveShortcuts(shortcutSource, shortcuts)
    }

    // --- Save Prompt ---

    private fun handleSavePromptInput(screen: IGMScreen.SavePrompt, button: String?): Boolean {
        val count = buildSettingsItems().size
        if (count == 0) return true
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            "btn_south" -> {
                when (screen.selectedIndex) {
                    0 -> { saveToPlatform(); showOsd(getString(R.string.osd_saved_for, platformName), OsdPosition.BottomCenter) }
                    1 -> { saveToGame(); showOsd(getString(R.string.osd_saved_for_game), OsdPosition.BottomCenter) }
                }
                frontendSnapshot = null
                shaderParamsDirty = false
                pop(); pop()
                true
            }
            "btn_east" -> {
                frontendSnapshot = null
                shaderParamsDirty = false
                pop(); pop()
                true
            }
            else -> true
        }
    }

    // --- Settings item builders ---

    private fun buildSettingsItems(): List<IGMSettingsItem> = when (val screen = currentScreen) {
        is IGMScreen.Settings -> IGMSettings.CATEGORIES.map { IGMSettingsItem(it) }
        is IGMScreen.Video -> buildList {
            add(IGMSettingsItem("Screen Scaling", scalingLabel()))
            add(IGMSettingsItem("Screen Sharpness", sharpnessLabel()))
            val shaderLabel = if (screenEffect == ScreenEffect.NONE || shaderPreset.isEmpty()) "Off"
                else File(shaderPreset).nameWithoutExtension
            add(IGMSettingsItem("Shader", shaderLabel))
            if (shaderParams.isNotEmpty()) add(IGMSettingsItem("Shader Settings"))
            add(IGMSettingsItem("Overlay", overlayLabel()))
        }
        is IGMScreen.Advanced -> buildList {
            if (controllerTypes.size > 1) {
                val ports = occupiedPorts()
                if (ports.size <= 1) {
                    add(IGMSettingsItem("Controller Type", deviceTypeLabel(0)))
                } else {
                    for (p in ports) add(IGMSettingsItem("P${p + 1} Controller", deviceTypeLabel(p)))
                }
            }
            add(IGMSettingsItem("Max FF Speed", "${maxFfSpeed}x"))
            add(IGMSettingsItem("Show FPS", if (showFpsBaseline) "On" else "Off"))
            add(IGMSettingsItem("Debug HUD", if (debugHud) "On" else "Off"))
        }
        is IGMScreen.ShaderSettings -> {
            if (shaderParams.isEmpty()) listOf(IGMSettingsItem("No parameters"))
            else shaderParams.map { p ->
                IGMSettingsItem(p.description, "%.2f".format(p.value))
            }
        }
        is IGMScreen.Emulator -> {
            if (emulatorHasCategories()) {
                val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
                val items = usedCategories.map { IGMSettingsItem(it.desc, hint = it.info.ifEmpty { null }) }.toMutableList()
                val uncategorized = coreOptions.filter { it.category.isEmpty() }
                if (uncategorized.isNotEmpty()) items.add(IGMSettingsItem("Other"))
                items
            } else if (coreOptions.isEmpty()) {
                listOf(IGMSettingsItem("No options available"))
            } else {
                coreOptions.map { opt ->
                    val label = opt.values.find { it.value == opt.selected }?.label ?: opt.selected
                    IGMSettingsItem(opt.desc, label, hint = opt.info.ifEmpty { null })
                }
            }
        }
        is IGMScreen.EmulatorCategory -> {
            val filtered = coreOptions.filter { it.category == screen.categoryKey }
            filtered.map { opt ->
                val label = opt.values.find { it.value == opt.selected }?.label ?: opt.selected
                IGMSettingsItem(opt.desc, label, hint = opt.info.ifEmpty { null })
            }
        }
        is IGMScreen.Shortcuts -> buildList {
            add(IGMSettingsItem("Source", sourceLabel(shortcutSource)))
            for (action in ShortcutAction.entries) {
                val chord = shortcuts[action]
                val label = if (chord.isNullOrEmpty()) "None"
                else chord.joinToString(" + ") { dev.cannoli.scorza.util.keyCodeName(it) }
                add(IGMSettingsItem(getString(action.labelRes), label))
            }
        }
        is IGMScreen.SavePrompt -> listOf(
            IGMSettingsItem("Save for $platformName"),
            IGMSettingsItem("Save for this game"),
            IGMSettingsItem("Discard")
        )
        is IGMScreen.Buttons -> buildButtonsItems(screen)
        else -> emptyList()
    }

    private fun buildButtonsItems(screen: IGMScreen.Buttons): List<IGMSettingsItem> = buildList {
        val canonicals = dev.cannoli.scorza.input.CanonicalButton.entries
            .filter { it != dev.cannoli.scorza.input.CanonicalButton.BTN_MENU }
        for (cb in canonicals) {
            val isListening = screen.listeningCanonical == cb.name
            val effective = inputRemap[cb]
                ?: dev.cannoli.scorza.input.runtime.CanonicalRetroMap.maskOf(cb)
            val value = when {
                isListening -> dev.cannoli.ui.ELLIPSIS
                effective == 0 -> "—"
                else -> retroMaskLabel(effective)
            }
            add(IGMSettingsItem(canonicalLabel(cb), value))
        }
    }

    private fun canonicalLabel(cb: dev.cannoli.scorza.input.CanonicalButton): String = when (cb) {
        dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH -> "South"
        dev.cannoli.scorza.input.CanonicalButton.BTN_EAST -> "East"
        dev.cannoli.scorza.input.CanonicalButton.BTN_WEST -> "West"
        dev.cannoli.scorza.input.CanonicalButton.BTN_NORTH -> "North"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L -> "L"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R -> "R"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L2 -> "L2"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R2 -> "R2"
        dev.cannoli.scorza.input.CanonicalButton.BTN_L3 -> "L3"
        dev.cannoli.scorza.input.CanonicalButton.BTN_R3 -> "R3"
        dev.cannoli.scorza.input.CanonicalButton.BTN_START -> "Start"
        dev.cannoli.scorza.input.CanonicalButton.BTN_SELECT -> "Select"
        dev.cannoli.scorza.input.CanonicalButton.BTN_UP -> "Up"
        dev.cannoli.scorza.input.CanonicalButton.BTN_DOWN -> "Down"
        dev.cannoli.scorza.input.CanonicalButton.BTN_LEFT -> "Left"
        dev.cannoli.scorza.input.CanonicalButton.BTN_RIGHT -> "Right"
        dev.cannoli.scorza.input.CanonicalButton.BTN_MENU -> "Menu"
    }

    private fun retroMaskLabel(mask: Int): String = when (mask) {
        RetroJoypad.RETRO_B -> "B"
        RetroJoypad.RETRO_A -> "A"
        RetroJoypad.RETRO_Y -> "Y"
        RetroJoypad.RETRO_X -> "X"
        RetroJoypad.RETRO_L -> "L"
        RetroJoypad.RETRO_R -> "R"
        RetroJoypad.RETRO_L2 -> "L2"
        RetroJoypad.RETRO_R2 -> "R2"
        RetroJoypad.RETRO_L3 -> "L3"
        RetroJoypad.RETRO_R3 -> "R3"
        RetroJoypad.RETRO_START -> "Start"
        RetroJoypad.RETRO_SELECT -> "Select"
        RetroJoypad.RETRO_UP -> "Up"
        RetroJoypad.RETRO_DOWN -> "Down"
        RetroJoypad.RETRO_LEFT -> "Left"
        RetroJoypad.RETRO_RIGHT -> "Right"
        else -> "?"
    }

    // --- Settings persistence ---

    private fun buildCurrentSettings(): OverrideManager.Settings {
        val optionMap = mutableMapOf<String, String>()
        for (opt in coreOptions) optionMap[opt.key] = opt.selected
        val paramMap = shaderParams.associate { it.id to it.value }

        return OverrideManager.Settings(
            scalingMode = scalingMode,
            screenEffect = screenEffect,
            sharpness = sharpness,
            debugHud = debugHud,
            showFps = showFpsBaseline,
            maxFfSpeed = maxFfSpeed,
            shaderPreset = shaderPreset,
            overlay = overlay,
            coreOptions = optionMap,
            shaderParams = paramMap,
            portDeviceTypes = portDeviceTypes,
            inputRemap = inputRemap,
        )
    }

    private fun saveToPlatform() {
        val settings = buildCurrentSettings()
        overrideManager.savePlatform(settings)
        platformBaseline = overrideManager.loadPlatformBaseline()
        activeInputRemap = settings.inputRemap
    }

    private fun saveToGame() {
        val settings = buildCurrentSettings()
        val baseline = platformBaseline ?: overrideManager.loadPlatformBaseline()
        overrideManager.saveGameDelta(settings, baseline)
        activeInputRemap = settings.inputRemap
    }

    private fun sourceLabel(source: OverrideSource): String = when (source) {
        OverrideSource.GLOBAL -> getString(R.string.override_source_global)
        OverrideSource.PLATFORM -> platformName
        OverrideSource.GAME -> getString(R.string.override_source_game)
    }

    private fun loadOverrides() {
        val settings = overrideManager.load()
        scalingMode = settings.scalingMode
        screenEffect = settings.screenEffect
        sharpness = settings.sharpness
        debugHud = settings.debugHud
        showFps = settings.showFps
        showFpsBaseline = settings.showFps
        maxFfSpeed = settings.maxFfSpeed
        shaderPreset = settings.shaderPreset
        overlay = settings.overlay
        shortcutSource = settings.shortcutSource
        shortcuts = settings.shortcuts
        portDeviceTypes = settings.portDeviceTypes
        inputRemap = settings.inputRemap
        activeInputRemap = settings.inputRemap

        for ((key, value) in settings.coreOptions) {
            runner.setCoreOption(key, value)
        }
        coreOptions = loadVisibleCoreOptions()
        shaderParams = emptyList()
        refreshShaderParams()
        applySavedShaderParams(settings.shaderParams)
        platformBaseline = overrideManager.loadPlatformBaseline()
    }

    private fun applyPortDeviceTypes() {
        val entries = portRouter.snapshotEntries()
        val occupied = entries.filter { !it.mapping.excludeFromGameplay }.map { it.port }.toSet()
        for (p in 0 until LibretroRunner.MAX_PORTS) {
            if (p !in occupied) continue
            val typeId = portDeviceTypes[p] ?: LibretroRunner.DEVICE_JOYPAD
            runner.setControllerPortDevice(p, typeId)
        }
    }

    private fun prepareVerticalModeReinit(): Pair<() -> Unit, () -> Unit>? {
        val coreName = File(corePath).nameWithoutExtension.lowercase()
        if (!coreName.contains("fbneo") && !coreName.contains("mame")) return null
        val opts = runner.getCoreOptions()
        val vertOpt = opts.find { "vertical" in it.desc.lowercase() } ?: return null
        if (vertOpt.values.size < 2) return null
        val original = vertOpt.selected
        val alt = vertOpt.values.first { it.value != original }.value
        return Pair(
            { runner.setCoreOption(vertOpt.key, alt) },
            { runner.setCoreOption(vertOpt.key, original) }
        )
    }

    // --- OSD / Undo ---

    private fun showOsd(message: String, position: OsdPosition = OsdPosition.TopCenter) {
        osdController.show(message, position)
    }

    private val raStartupHandler = Handler(Looper.getMainLooper())
    private var raStartupTimeout: Runnable? = null
    private var raStartupDisplayName: String? = null

    private fun scheduleRaStartupOsd(displayName: String) {
        raStartupTimeout?.let { raStartupHandler.removeCallbacks(it) }
        raStartupDisplayName = displayName
        val ra = raManager
        if (ra != null && ra.isMemoryInitialized && ra.gameId > 0 && ra.getAchievements().isNotEmpty()) {
            onRaDetectionReady()
            return
        }
        val timeout = Runnable {
            if (raStartupDisplayName != null) {
                showOsd(raTimeoutOsdText(), OsdPosition.TopCenter)
                raStartupDisplayName = null
                raStartupTimeout = null
            }
        }
        raStartupTimeout = timeout
        raStartupHandler.postDelayed(timeout, 5000L)
    }

    private fun raTimeoutOsdText(): String {
        val ra = raManager ?: return getString(R.string.ra_init_failed)
        return when {
            ra.isOffline && ra.gameId <= 0 -> getString(R.string.ra_load_offline_pending)
            ra.gameId <= 0 -> getString(R.string.ra_load_not_recognized)
            ra.getAchievements().isEmpty() -> getString(R.string.ra_load_no_achievements)
            else -> getString(R.string.ra_init_failed)
        }
    }

    private fun onRaDetectionReady() {
        val name = raStartupDisplayName ?: return
        raStartupTimeout?.let { raStartupHandler.removeCallbacks(it) }
        raStartupTimeout = null
        raStartupDisplayName = null
        val ra = raManager
        if (ra == null || ra.gameId <= 0) {
            showOsd(getString(R.string.ra_load_not_recognized), OsdPosition.TopCenter)
            return
        }
        val achievements = ra.getAchievements()
        if (achievements.isEmpty()) {
            showOsd(getString(R.string.ra_load_no_achievements), OsdPosition.TopCenter)
            return
        }
        val unlocked = achievements.count { it.unlocked }
        showOsd(
            getString(R.string.ra_login_success, name, ra.getStatus(), unlocked, achievements.size),
            OsdPosition.TopStart
        )
    }

    private fun startUndoTimer(durationMs: Long = 60_000) {
        undoHandler.removeCallbacks(clearUndoRunnable)
        undoHandler.postDelayed(clearUndoRunnable, durationMs)
    }

    private fun performUndo() {
        val type = undoType ?: return
        val label = when (type) {
            UndoType.SAVE -> "Undo Save"
            UndoType.LOAD -> "Undo Load"
            UndoType.RESET -> "Undo Reset"
        }
        when (type) {
            UndoType.SAVE -> undoSlot?.let { slotManager.performUndoSave(it) }
            UndoType.LOAD, UndoType.RESET -> slotManager.performUndoLoad(runner)
        }
        clearUndo()
        refreshSlotInfo()
        showOsd(label, OsdPosition.BottomCenter)
        closeAll()
    }

    private fun clearUndo() {
        undoType = null
        undoSlot = null
        undoHandler.removeCallbacks(clearUndoRunnable)
        slotManager.clearUndoCache()
    }

    // --- Lifecycle ---

    private fun cleanup() {
        if (cleaned || loading) return
        cleaned = true
        sessionLog.log("RA cleanup: raManager=${raManager != null}")
        raManager?.unloadGame()
        raManager?.destroy()
        raManager = null
        if (sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
        runner.stopAudio()
        runner.unloadGame()
        runner.deinit()
        File(cacheDir, "rom_cache").deleteRecursively()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun quit() {
        isRunning = false
        if (cannoliRoot.isNotEmpty()) dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).quickResumeFile.delete()
        cleanup()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (::sessionLog.isInitialized) sessionLog.log("onPause")
        if (!loading && !cleaned && screenStack.isEmpty()) openMenu()
        stopVsyncPacer()
        glSurfaceView?.onPause()
        if (!loading && !cleaned && sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
        if (::menuNavigationPoller.isInitialized) menuNavigationPoller.stop()
        // Cancel any in-flight stick auto-repeat so it does not keep firing dispatcher callbacks
        // after MainActivity has rewired them.
        if (::stickAutoRepeat.isInitialized) stickAutoRepeat.stop()
        if (::controllerBridge.isInitialized) {
            controllerBridge.onDeviceAdded = null
            controllerBridge.onDeviceRemoved = null
        }
    }

    override fun onStop() {
        super.onStop()
        if (::sessionLog.isInitialized) sessionLog.log("onStop")
        if (!loading && !cleaned && stateBasePath.isNotEmpty() && !autoSavedOnStop) {
            File("$stateBasePath.auto").parentFile?.mkdirs()
            runner.saveState("$stateBasePath.auto")
            // TODO: serialize RA progress once we adopt a container format
            // raManager?.serializeProgress()?.let { data ->
            //     try { File("$stateBasePath.auto.ra").writeBytes(data) } catch (_: Exception) {}
            // }
            autoSavedOnStop = true
            if (cannoliRoot.isNotEmpty() && romPath.isNotEmpty()) {
                val f = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).quickResumeFile
                f.parentFile?.mkdirs()
                f.writeText("$romPath\n$platformTag")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume(); overridePendingTransition(0, 0); glSurfaceView?.onResume(); startVsyncPacer(); goFullscreen()
        if (::sessionLog.isInitialized) sessionLog.log("onResume")
        if (autoSavedOnStop && cannoliRoot.isNotEmpty()) dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).quickResumeFile.delete()
        autoSavedOnStop = false
        if (::inputDispatcher.isInitialized) {
            wireDispatcherForIGM()
            menuNavigationPoller.start()
        }
        if (::controllerBridge.isInitialized) {
            controllerBridge.onDeviceAdded = { device ->
                val port = portRouter.portFor(device.androidDeviceId)
                if (!device.isBuiltIn) {
                    val portLabel = port?.let { "P${it + 1}" } ?: "-"
                    val name = port?.let { portRouter.mappingForPort(it)?.displayName?.takeIf { n -> n.isNotEmpty() } }
                        ?: device.name.ifEmpty { getString(R.string.device_controller) }
                    showOsd(getString(R.string.osd_controller_connected_named, name, portLabel))
                }
                if (port != null && ::runner.isInitialized) {
                    val typeId = portDeviceTypes[port] ?: LibretroRunner.DEVICE_JOYPAD
                    runner.setControllerPortDevice(port, typeId)
                }
            }
            controllerBridge.onDeviceRemoved = { departed ->
                val msg = departed.port?.let {
                    getString(R.string.osd_controller_disconnected_port, it + 1, departed.displayName)
                } ?: getString(R.string.osd_controller_disconnected, departed.displayName)
                showOsd(msg)
                val port = departed.port
                if (port != null && ::runner.isInitialized && !loading) {
                    runner.setInput(port, 0)
                    runner.setControllerPortDevice(port, LibretroRunner.DEVICE_NONE)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::sessionLog.isInitialized) sessionLog.log("onSaveInstanceState (system will recreate)")
    }

    override fun onDestroy() {
        audioStatsHandler.removeCallbacks(audioStatsRunnable)
        raStartupTimeout?.let { raStartupHandler.removeCallbacks(it) }
        if (::sessionLog.isInitialized) {
            sessionLog.log("onDestroy (isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations)")
            sessionLog.close()
        }
        isRunning = false
        if (::controllerBridge.isInitialized) {
            controllerBridge.onDeviceAdded = null
            controllerBridge.onDeviceRemoved = null
        }
        super.onDestroy()
        cleanup()
    }


    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Order of port slot rows on the Reassign Players screen: P1 .. P4. */
    private val reassignPortCount = 4

    private fun handleReassignPlayersInput(screen: IGMScreen.ReassignPlayers, button: String?): Boolean {
        val count = reassignPortCount
        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
                true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
                true
            }
            "btn_south" -> {
                if (screen.swapWithIndex < 0) {
                    // Begin a swap: arm the row currently selected.
                    if (deviceForPort(screen.selectedIndex) != null) {
                        replaceTop(screen.copy(swapWithIndex = screen.selectedIndex))
                    }
                } else {
                    val from = screen.swapWithIndex
                    val to = screen.selectedIndex
                    if (from != to) {
                        val deviceId = deviceForPort(from)
                        if (deviceId != null) {
                            portRouter.reassign(deviceId, to)
                            portPressedKeys[from].clear()
                            portPressedKeys[to].clear()
                            runner.setInput(from, 0)
                            runner.setInput(to, 0)
                        }
                    }
                    replaceTop(screen.copy(swapWithIndex = -1))
                }
                true
            }
            "btn_east" -> {
                if (screen.swapWithIndex >= 0) {
                    replaceTop(screen.copy(swapWithIndex = -1))
                } else {
                    pop()
                }
                true
            }
            else -> true
        }
    }

    private fun deviceForPort(port: Int): Int? =
        portRouter.snapshotEntries().firstOrNull { it.port == port }?.androidDeviceId

    private val buttonsCanonicals: List<dev.cannoli.scorza.input.CanonicalButton>
        get() = dev.cannoli.scorza.input.CanonicalButton.entries
            .filter { it != dev.cannoli.scorza.input.CanonicalButton.BTN_MENU }

    private fun resolveCanonicalForBinding(keyCode: Int, deviceId: Int): dev.cannoli.scorza.input.CanonicalButton? {
        val mapping = portRouter.mappingFor(deviceId) ?: activeMappingHolder.active.value ?: return null
        return mapping.bindings.entries.firstOrNull { (_, bindings) ->
            bindings.any { it is dev.cannoli.scorza.input.InputBinding.Button && it.keyCode == keyCode }
        }?.key
    }

    private fun handleButtonsInput(
        screen: IGMScreen.Buttons,
        keyCode: Int,
        deviceId: Int,
        repeatCount: Int,
        button: String?,
    ): Boolean {
        val canonicals = buttonsCanonicals
        val count = canonicals.size
        if (count == 0) return true

        if (screen.listeningCanonical != null) {
            if (repeatCount > 0) return true
            val pressed = resolveCanonicalForBinding(keyCode, deviceId) ?: return true
            val target = canonicals.firstOrNull { it.name == screen.listeningCanonical } ?: return true
            inputRemap = inputRemap.toMutableMap().also {
                it[target] = dev.cannoli.scorza.input.runtime.CanonicalRetroMap.maskOf(pressed)
            }
            replaceTop(screen.copy(listeningCanonical = null))
            return true
        }

        return when (button) {
            "btn_up" -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count))
                true
            }
            "btn_down" -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count))
                true
            }
            "btn_south" -> {
                val cb = canonicals.getOrNull(screen.selectedIndex) ?: return true
                replaceTop(screen.copy(listeningCanonical = cb.name))
                true
            }
            "btn_north" -> {
                val cb = canonicals.getOrNull(screen.selectedIndex) ?: return true
                inputRemap = inputRemap.toMutableMap().also { it[cb] = 0 }
                true
            }
            "btn_west" -> {
                if (inputRemap.isNotEmpty()) {
                    inputRemap = emptyMap()
                    showOsd(getString(R.string.osd_buttons_reset), OsdPosition.BottomCenter)
                }
                true
            }
            "btn_east" -> { pop(); true }
            else -> true
        }
    }

}
