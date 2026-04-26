package dev.cannoli.scorza

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.input.InputRouter
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.PermissionScreen
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.theme.COLOR_PRESETS
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.colorToArgbLong
import dev.cannoli.ui.theme.hexToColor
import dev.cannoli.ui.theme.initFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var platformConfig: PlatformConfig
    private lateinit var systemListViewModel: SystemListViewModel
    private lateinit var gameListViewModel: GameListViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private val inputTesterViewModel = dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel()
    private val setupCoordinator: dev.cannoli.scorza.setup.SetupCoordinator by lazy {
        dev.cannoli.scorza.setup.SetupCoordinator(this, settings, ioScope)
    }
    private lateinit var controllerManager: dev.cannoli.scorza.input.ControllerManager
    private lateinit var inputTesterController: dev.cannoli.scorza.input.InputTesterController
    private lateinit var updateManager: dev.cannoli.scorza.updater.UpdateManager

    private lateinit var retroArchLauncher: RetroArchLauncher
    private lateinit var emuLauncher: EmuLauncher
    private lateinit var apkLauncher: ApkLauncher
    private lateinit var installedCoreService: InstalledCoreService

    private lateinit var inputHandler: InputHandler
    private lateinit var atomicRename: AtomicRename
    private val isTv: Boolean by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    private val screenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)

    private val preInitScreenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)
    private val preInitDialogState = MutableStateFlow<DialogState>(DialogState.None)
    private lateinit var nav: NavigationController
    private lateinit var router: InputRouter

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlButtons = LibretroInput().buttons
    private val controlButtonCount = controlButtons.size
    private var pendingRecentlyPlayedReorder = false
    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var currentFirstVisible = 0
    private var currentPageSize = 10
    private var permissionGranted by mutableStateOf(false)
    private var coldStart = true

    private val osdHandler = Handler(Looper.getMainLooper())
    private val clearOsdRunnable = Runnable {
        if (::nav.isInitialized) nav.osdMessage = null
    }

    private fun showOsd(message: String, durationMs: Long = 3000) {
        osdHandler.removeCallbacks(clearOsdRunnable)
        if (::nav.isInitialized) nav.osdMessage = message
        osdHandler.postDelayed(clearOsdRunnable, durationMs)
    }

    private var lastKeyRepeatCount: Int = 0

    private val bindingController by lazy {
        dev.cannoli.scorza.input.BindingController(
            navProvider = { if (::nav.isInitialized) nav else NavigationController() },
            controlButtons = controlButtons,
            swapConfirmBackProvider = { inputHandler.swapConfirmBack },
            showOsd = { text, durationMs -> showOsd(text, durationMs = durationMs) },
            cannotStealConfirmText = getString(R.string.osd_cannot_steal_confirm),
        )
    }
    private fun cancelShortcutListening() = bindingController.cancelShortcutListening()

    private lateinit var globalOverrides: GlobalOverridesManager
    private lateinit var profileManager: dev.cannoli.scorza.input.ProfileManager
    private lateinit var autoconfigLoader: dev.cannoli.scorza.input.autoconfig.AutoconfigLoader
    private lateinit var autoconfigMatcher: dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher
    private lateinit var launchManager: LaunchManager

    private fun launchSelected(item: dev.cannoli.scorza.model.ListItem, resume: Boolean = false): DialogState? = when (item) {
        is dev.cannoli.scorza.model.ListItem.RomItem ->
            if (resume) launchManager.resumeRom(item.rom) else launchManager.launchRom(item.rom)
        is dev.cannoli.scorza.model.ListItem.AppItem -> launchManager.launchApp(item.app)
        else -> null
    }

    private fun invalidateAllLibraryCaches() {
        artworkLookup.invalidateAll()
        arcadeTitleLookup.invalidateAll()
    }

    private fun resolvePathToRef(path: String): dev.cannoli.scorza.db.LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { dev.cannoli.scorza.model.AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { dev.cannoli.scorza.db.LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { dev.cannoli.scorza.db.LibraryRef.Rom(it.id) }
        }
    }

    private fun recordRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedRepository.record(it) }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
        }
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            permissionGranted = true
            afterPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()

        settings = SettingsRepository(this)
        initFonts(assets)

        settingsViewModel = SettingsViewModel(settings)
        profileManager = dev.cannoli.scorza.input.ProfileManager(settings.sdCardRoot)
        autoconfigLoader = dev.cannoli.scorza.input.autoconfig.AutoconfigLoader(
            dev.cannoli.scorza.input.autoconfig.AssetCfgSource(this)
        )
        autoconfigMatcher = dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher(autoconfigLoader.entries())
        inputHandler = InputHandler(
            getButtonMappings = {
                val activeStack = if (::nav.isInitialized) nav.screenStack else screenStack
                val screen = activeStack.lastOrNull() as? LauncherScreen.ControlBinding
                if (screen != null && screen.profileName == dev.cannoli.scorza.input.ProfileManager.NAVIGATION) screen.controls
                else profileManager.readControls(dev.cannoli.scorza.input.ProfileManager.NAVIGATION)
            }
        )
        setupWireInput()

        lifecycleScope.launch {
            settingsViewModel.appSettings.collect { appSettings ->
                inputHandler.swapConfirmBack = appSettings.confirmButton == dev.cannoli.ui.ConfirmButton.EAST
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        if (hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
        }

        setContent {
            val appFont by settingsViewModel.appSettings.collectAsState()
            CannoliTheme(fontFamily = appFont.fontFamily) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionGranted) {
                        PermissionScreen()
                    } else {
                        val updateInfo = if (::updateManager.isInitialized) updateManager.updateAvailable.collectAsState().value else null
                        val dlProgress = if (::updateManager.isInitialized) updateManager.downloadProgress.collectAsState().value else 0f
                        val dlError = if (::updateManager.isInitialized) updateManager.downloadError.collectAsState().value else null
                        val navScreen = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull() ?: LauncherScreen.SystemList
                        val navDialogState = if (::nav.isInitialized) nav.dialogState else preInitDialogState
                        val navResumableGames = if (::nav.isInitialized) nav.resumableGames else emptySet()
                        val navOsdMessage = if (::nav.isInitialized) nav.osdMessage else null
                        AppNavGraph(
                            currentScreen = navScreen,
                            systemListViewModel = if (::systemListViewModel.isInitialized) systemListViewModel else null,
                            gameListViewModel = if (::gameListViewModel.isInitialized) gameListViewModel else null,
                            inputTesterViewModel = inputTesterViewModel,
                            onExitInputTester = {
                                if (::nav.isInitialized) {
                                    if (nav.screenStack.size > 1) nav.screenStack.removeAt(nav.screenStack.lastIndex)
                                } else {
                                    if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex)
                                }
                            },
                            settingsViewModel = settingsViewModel,
                            dialogState = navDialogState,
                            onVisibleRangeChanged = { first, count, full ->
                                if (::nav.isInitialized) {
                                    nav.currentFirstVisible = first
                                    if (full) nav.currentPageSize = count
                                } else {
                                    currentFirstVisible = first
                                    if (full) currentPageSize = count
                                }
                            },
                            resumableGames = navResumableGames,
                            updateAvailable = updateInfo != null,
                            downloadProgress = dlProgress ?: 0f,
                            downloadError = dlError,
                            osdMessage = navOsdMessage,
                        )
                    }
                }
            }
        }
    }

    private fun activeScreenStack() = if (::nav.isInitialized) nav.screenStack else preInitScreenStack

    private fun afterPermissionGranted() {
        if (settings.setupCompleted) {
            initializeApp()
        } else if (File(settings.sdCardRoot, "Config/settings.json").exists()) {
            settings.setupCompleted = true
            initializeApp()
        } else {
            val detected = setupCoordinator.detectExistingCannoli()
            if (detected != null) {
                settings.sdCardRoot = detected
                settings.setupCompleted = true
                initializeApp()
            } else {
                val volumes = setupCoordinator.detectStorageVolumes() + ("Custom" to "")
                val stack = activeScreenStack()
                stack.clear()
                stack.add(LauncherScreen.Setup(volumes = volumes))
            }
        }
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        activeScreenStack().add(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun onDirectoryBrowserResult(purpose: BrowsePurpose, path: String) {
        val resolved = if (setupCoordinator.isVolumeRoot(path)) path + "Cannoli/" else path
        val stack = activeScreenStack()
        when (purpose) {
            BrowsePurpose.SD_ROOT -> {
                settings.sdCardRoot = resolved
                val activeDialogState = if (::nav.isInitialized) nav.dialogState else preInitDialogState
                activeDialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.ROM_DIRECTORY -> {
                settings.romDirectory = resolved
                invalidateAllLibraryCaches()
                val activeDialogState = if (::nav.isInitialized) nav.dialogState else preInitDialogState
                activeDialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.SETUP -> {
                val idx = stack.indexOfLast { it is LauncherScreen.Setup }
                if (idx >= 0) {
                    val setup = stack[idx] as LauncherScreen.Setup
                    val resolvedPath = if (resolved.endsWith("/")) resolved else "$resolved/"
                    stack[idx] = setup.copy(customPath = resolvedPath)
                }
            }
        }
    }

    private fun startInstalling(targetPath: String) {
        setupCoordinator.startInstalling(
            targetPath = targetPath,
            onProgress = { progress, label ->
                val stack = activeScreenStack()
                val screen = stack.lastOrNull() as? LauncherScreen.Installing ?: return@startInstalling
                stack[stack.lastIndex] = screen.copy(progress = progress, statusLabel = label)
            },
            onFinished = { services ->
                platformConfig = services.platformConfig
                retroArchLauncher = services.retroArchLauncher
                emuLauncher = services.emuLauncher
                apkLauncher = services.apkLauncher
                installedCoreService = services.installedCoreService
                launchManager = services.launchManager
                val stack = activeScreenStack()
                val screen = stack.lastOrNull() as? LauncherScreen.Installing ?: return@startInstalling
                stack[stack.lastIndex] = screen.copy(
                    progress = 1f,
                    statusLabel = "Cannoli is now ready to be garnished!",
                    finished = true
                )
            },
        )
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (!permissionGranted && hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
            return
        }
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        if (::launchManager.isInitialized) launchManager.launching = false
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, opts)
            return
        }
        if (::settings.isInitialized) {
            settings.reload()
            if (::settingsViewModel.isInitialized) settingsViewModel.load()
            val activeDialogState = if (::nav.isInitialized) nav.dialogState else preInitDialogState
            if (activeDialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
                activeDialogState.value = DialogState.None
            }
        }
        if (::systemListViewModel.isInitialized) {
            rescanSystemList()
            val activeScreen = if (::nav.isInitialized) nav.currentScreen else screenStack.lastOrNull()
            if (activeScreen is LauncherScreen.GameList) {
                gameListViewModel.reload { scanResumableGames() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val pending = if (::nav.isInitialized) nav.pendingRecentlyPlayedReorder else pendingRecentlyPlayedReorder
        if (pending) {
            if (::nav.isInitialized) nav.pendingRecentlyPlayedReorder = false else pendingRecentlyPlayedReorder = false
            gameListViewModel.moveSelectedToTop()
        }
    }

    override fun onDestroy() {
        if (::controllerManager.isInitialized) {
            (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
                .unregisterInputDeviceListener(controllerManager)
        }
        super.onDestroy()
        unregisterCoreQueryReceiver()
        settings.shutdown()
        ioScope.cancel()
        if (::systemListViewModel.isInitialized) systemListViewModel.close()
        if (::gameListViewModel.isInitialized) gameListViewModel.close()
        dev.cannoli.scorza.server.KitchenManager.stop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!InputHandler.isGamepadEvent(event)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val currentScreenForKey = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull()
                    if (currentScreenForKey is LauncherScreen.InputTester) {
                        inputTesterController.dispatchKey(event, down = event.action == KeyEvent.ACTION_DOWN)
                    } else if (isTv && event.action == KeyEvent.ACTION_DOWN && permissionGranted) {
                        inputHandler.onBack()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!permissionGranted) {
            requestStoragePermission()
            return true
        }
        val currentScreenForKey = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull()
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = true)
            return true
        }
        if (::router.isInitialized && bindingController.handleKeyDown(keyCode)) {
            return true
        }
        if (event.repeatCount > 0 && currentScreenForKey is LauncherScreen.ShortcutBinding && !currentScreenForKey.listening) {
            return true
        }
        if (::nav.isInitialized) {
            nav.lastKeyRepeatCount = event.repeatCount
        } else {
            lastKeyRepeatCount = event.repeatCount
        }
        if (isTv && !InputHandler.isGamepadEvent(event)) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { inputHandler.onWest(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { inputHandler.onNorth(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { inputHandler.onStart(); return true }
            }
        }
        if (inputHandler.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val currentScreenForKey = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull()
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = false)
            return true
        }
        if (inputHandler.resolveButton(event) == "btn_select") {
            if (::router.isInitialized) router.onSelectUp()
            return true
        }
        if (currentScreenForKey is LauncherScreen.ShortcutBinding && currentScreenForKey.listening && currentScreenForKey.heldKeys.contains(keyCode)) {
            cancelShortcutListening()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private val triggerL2HeldDevices = mutableSetOf<Int>()
    private val triggerR2HeldDevices = mutableSetOf<Int>()

    private fun syncBindingTrigger(deviceId: Int, keyCode: Int, value: Float, held: MutableSet<Int>) {
        val wasHeld = deviceId in held
        if (value > 0.5f && !wasHeld) {
            held.add(deviceId)
            bindingController.handleKeyDown(keyCode)
        } else if (value < 0.3f && wasHeld) {
            held.remove(deviceId)
            val currentScreenForTrigger = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull()
            if (currentScreenForTrigger is LauncherScreen.ShortcutBinding && currentScreenForTrigger.listening && currentScreenForTrigger.heldKeys.contains(keyCode)) {
                cancelShortcutListening()
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val source = event.source
        val isJoystick =
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        val currentScreenForMotion = if (::nav.isInitialized) nav.currentScreen else preInitScreenStack.lastOrNull()
        if (currentScreenForMotion is LauncherScreen.ShortcutBinding || currentScreenForMotion is LauncherScreen.ControlBinding) {
            val lt = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE),
            )
            val rt = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_GAS),
            )
            syncBindingTrigger(event.deviceId, KeyEvent.KEYCODE_BUTTON_L2, lt, triggerL2HeldDevices)
            syncBindingTrigger(event.deviceId, KeyEvent.KEYCODE_BUTTON_R2, rt, triggerR2HeldDevices)
        }

        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)
        dev.cannoli.scorza.util.DebugLog.init(root.absolutePath, settings.debugLogging)
        dev.cannoli.scorza.util.ScanLog.init(root.absolutePath)

        retroArchLauncher = RetroArchLauncher(this) { settings.retroArchPackage }
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        val coreInfo = dev.cannoli.scorza.config.CoreInfoRepository(assets, filesDir, File(applicationInfo.sourceDir).lastModified())
        coreInfo.load()
        val bundledCoresDir = LaunchManager.extractBundledCores(this)
        platformConfig = PlatformConfig(root, assets, coreInfo, bundledCoresDir)
        platformConfig.load()

        installedCoreService = InstalledCoreService(this)
        launchManager = LaunchManager(this, settings, platformConfig, retroArchLauncher, emuLauncher, apkLauncher, installedCoreService)

        val romDir = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(root, "Roms")
        launchManager.syncRetroArchAssets(root)
        launchManager.syncRetroArchConfig(root)

        ioScope.launch { dev.cannoli.scorza.util.DirectoryLayout.ensure(root, romDir, assets, platformConfig) }

        cannoliDatabase = dev.cannoli.scorza.db.CannoliDatabase(root)
        artworkLookup = dev.cannoli.scorza.util.ArtworkLookup(root)
        arcadeTitleLookup = dev.cannoli.scorza.util.ArcadeTitleLookup(root)
        romsRepository = dev.cannoli.scorza.db.RomsRepository(romDir, cannoliDatabase, artworkLookup)
        val romWalker = dev.cannoli.scorza.util.RomDirectoryWalker(root, romDir, arcadeTitleLookup).also {
            it.loadIgnoreLists(assets)
        }
        romScanner = dev.cannoli.scorza.db.RomScanner(cannoliDatabase, romWalker, artworkLookup)
        appsRepository = dev.cannoli.scorza.db.AppsRepository(cannoliDatabase)
        collectionsRepository = dev.cannoli.scorza.db.CollectionsRepository(cannoliDatabase)
        recentlyPlayedRepository = dev.cannoli.scorza.db.RecentlyPlayedRepository(cannoliDatabase)
        runImporterThenContinue(root, romDir)
    }

    private lateinit var cannoliDatabase: dev.cannoli.scorza.db.CannoliDatabase
    private lateinit var artworkLookup: dev.cannoli.scorza.util.ArtworkLookup
    private lateinit var arcadeTitleLookup: dev.cannoli.scorza.util.ArcadeTitleLookup
    private lateinit var romsRepository: dev.cannoli.scorza.db.RomsRepository
    private lateinit var romScanner: dev.cannoli.scorza.db.RomScanner
    private lateinit var appsRepository: dev.cannoli.scorza.db.AppsRepository
    private lateinit var collectionsRepository: dev.cannoli.scorza.db.CollectionsRepository
    private lateinit var recentlyPlayedRepository: dev.cannoli.scorza.db.RecentlyPlayedRepository

    private fun runImporterThenContinue(root: File, romDir: File) {
        val importer = dev.cannoli.scorza.db.importer.Importer(
            cannoliRoot = root,
            romDirectory = romDir,
            db = cannoliDatabase,
            platformConfig = platformConfig,
            romScanner = romScanner,
            onProgress = dev.cannoli.scorza.db.importer.ImportProgress { progress, label ->
                runOnUiThread {
                    val stack = activeScreenStack()
                    val top = stack.lastOrNull()
                    if (top is LauncherScreen.Housekeeping &&
                        top.kind == dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION) {
                        stack[stack.lastIndex] = top.copy(progress = progress, statusLabel = label)
                    }
                }
            },
        )

        activeScreenStack().add(
            LauncherScreen.Housekeeping(
                kind = dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION,
                progress = 0f,
                statusLabel = "Preparing",
            )
        )

        ioScope.launch {
            val result = importer.run()
            withContext(Dispatchers.Main) {
                val stack = activeScreenStack()
                val top = stack.lastOrNull()
                if (top is LauncherScreen.Housekeeping) stack.removeAt(stack.lastIndex)
                if (result is dev.cannoli.scorza.db.importer.ImportResult.Failure) {
                    dev.cannoli.scorza.util.ScanLog.write("ERROR import returned Failure: ${result.cause.message}")
                }
                finishInitializeApp()
            }
        }
    }

    private fun finishInitializeApp() {
        if (screenStack.firstOrNull() !is LauncherScreen.SystemList) {
            screenStack.clear()
            screenStack.add(LauncherScreen.SystemList)
        }

        val root = File(settings.sdCardRoot)

        ioScope.launch {
            installedCoreService.queryAllPackages()
            platformConfig.purgeStaleRaMappings(installedCoreService.installedCores)
        }

        val romDir = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(root, "Roms")
        systemListViewModel = SystemListViewModel(
            romsRepository = romsRepository,
            romScanner = romScanner,
            appsRepository = appsRepository,
            collectionsRepository = collectionsRepository,
            recentlyPlayedRepository = recentlyPlayedRepository,
            platformConfig = platformConfig,
            romDirectory = romDir,
        )
        gameListViewModel = GameListViewModel(
            romsRepository = romsRepository,
            romScanner = romScanner,
            appsRepository = appsRepository,
            collectionsRepository = collectionsRepository,
            recentlyPlayedRepository = recentlyPlayedRepository,
            platformConfig = platformConfig,
            resources = resources,
            isArcadeTag = { platformConfig.isArcade(it) },
        )
        controllerManager = dev.cannoli.scorza.input.ControllerManager()
        controllerManager.loadBlacklist(this)
        controllerManager.initialize()
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .registerInputDeviceListener(controllerManager, android.os.Handler(android.os.Looper.getMainLooper()))
        inputTesterController = dev.cannoli.scorza.input.InputTesterController(
            viewModel = inputTesterViewModel,
            controllerManager = controllerManager,
            profileManager = profileManager,
            inputHandler = inputHandler,
            unknownDeviceName = getString(R.string.input_tester_device_unknown),
            keyboardDeviceName = getString(R.string.input_tester_device_keyboard),
        )
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        settingsViewModel.reinitialize(root, packageManager, packageName, collectionsRepository)
        updateManager = dev.cannoli.scorza.updater.UpdateManager(this, settings)

        ioScope.launch {
            updateManager.updateAvailable.collect { info ->
                settingsViewModel.updateInfo = info
            }
        }

        if (updateManager.shouldAutoCheck()) {
            ioScope.launch { updateManager.checkForUpdate() }
        }

        atomicRename = AtomicRename(root)

        globalOverrides = GlobalOverridesManager { settings.sdCardRoot }
        profileManager.reinitialize(settings.sdCardRoot)

        rescanSystemList()

        nav = dev.cannoli.scorza.navigation.NavigationController()
        nav.screenStack.clear()
        nav.screenStack.add(LauncherScreen.SystemList)

        val quickResume = File(root, "Config/State/quick_resume.txt")
        if (quickResume.exists()) {
            val lines = try { quickResume.readLines() } catch (_: Exception) { emptyList() }
            quickResume.delete()
            if (lines.size >= 2) {
                val romFile = File(lines[0])
                if (romFile.exists()) {
                    val rom = romsRepository.gameByPath(romFile.absolutePath)
                    if (rom != null) {
                        val errorDialog = launchManager.resumeRom(rom)
                        if (errorDialog != null) {
                            nav.dialogState.value = errorDialog
                        } else {
                            recordRecentlyPlayedByPath(romFile.absolutePath)
                        }
                    }
                }
            }
        }

        val rescanSystemListLambda: () -> Unit = { rescanSystemList() }
        val scanResumableGamesLambda: () -> Unit = { scanResumableGames() }
        val invalidateAllLibraryCachesLambda: () -> Unit = { invalidateAllLibraryCaches() }
        val validateFghStemLambda: () -> String? = { validateFghStem() }
        val hasActiveVpnLambda: () -> Boolean = { hasActiveVpn() }

        val startRaLogin: (String, String) -> Unit = { username, password ->
            val ra = RetroAchievementsManager(
                onLogin = { success, nameOrError, token ->
                    if (success && token != null) {
                        settings.raUsername = nameOrError
                        settings.raToken = token
                        settings.raPassword = password
                        settingsViewModel.raPassword = ""
                        nav.dialogState.value = DialogState.RAAccount(username = nameOrError)
                    } else {
                        nav.dialogState.value = DialogState.RALoggingIn(message = "Invalid username or password")
                    }
                    loginPollHandler.removeCallbacks(loginPollRunnable)
                    loginManager?.destroy()
                    loginManager = null
                }
            )
            ra.init()
            ra.loginWithPassword(username, password)
            loginManager = ra
            loginPollHandler.postDelayed(loginPollRunnable, 100)
            nav.dialogState.value = DialogState.RALoggingIn()
        }

        val openColorPickerLambda: (String) -> Unit = { settingKey -> openColorPicker(settingKey) }

        var systemListRenameCallback: ((DialogState.RenameInput) -> Unit) = {}

        val dialogHandler = dev.cannoli.scorza.input.DialogInputHandler(
            nav = nav,
            ioScope = ioScope,
            context = this,
            settings = settings,
            scanner = romScanner,
            collectionManager = collectionsRepository,
            recentlyPlayedManager = recentlyPlayedRepository,
            platformResolver = platformConfig,
            installedCoreService = installedCoreService,
            launchManager = launchManager,
            updateManager = updateManager,
            profileManager = profileManager,
            atomicRename = atomicRename,
            settingsViewModel = settingsViewModel,
            gameListViewModel = gameListViewModel,
            systemListViewModel = systemListViewModel,
            controllerManager = controllerManager,
            autoconfigMatcher = autoconfigMatcher,
            romsRepository = romsRepository,
            appsRepository = appsRepository,
            onRescanSystemList = rescanSystemListLambda,
            onFinishAffinity = { finishAffinity() },
            onRestartApp = {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val opts = android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
                    startActivity(intent, opts)
                    Runtime.getRuntime().exit(0)
                }
            },
            openColorPickerFn = openColorPickerLambda,
            onSystemListRename = { state -> systemListRenameCallback(state) },
            onStartRaLogin = startRaLogin,
        )

        val systemListHandler = dev.cannoli.scorza.input.screen.SystemListInputHandler(
            nav = nav,
            ioScope = ioScope,
            settings = settings,
            collectionsRepository = collectionsRepository,
            platformConfig = platformConfig,
            updateManager = updateManager,
            systemListViewModel = systemListViewModel,
            gameListViewModel = gameListViewModel,
            settingsViewModel = settingsViewModel,
            onRescanSystemList = rescanSystemListLambda,
            onScanResumableGames = scanResumableGamesLambda,
            onValidateFghStem = validateFghStemLambda,
            onLaunchSelected = { item, resume -> launchSelected(item, resume) },
            onRecordRecentlyPlayedByPath = { path -> recordRecentlyPlayedByPath(path) },
            onOpenKitchen = {
                val km = dev.cannoli.scorza.server.KitchenManager
                val sdRoot = File(settings.sdCardRoot)
                if (!km.isRunning) km.toggle(sdRoot, assets, settings.kitchenCodeBypass)
                else km.setCodeBypass(settings.kitchenCodeBypass)
                nav.dialogState.value = DialogState.Kitchen(
                    urls = km.getUrls(hasActiveVpnLambda()),
                    pin = km.pin,
                    requirePin = !settings.kitchenCodeBypass
                )
            },
            onSetPendingFghItem = { item -> dialogHandler.pendingFghItem = item },
        )
        systemListRenameCallback = { state -> systemListHandler.handleRename(state) }

        val gameListHandler = dev.cannoli.scorza.input.screen.GameListInputHandler(
            nav = nav,
            ioScope = ioScope,
            settings = settings,
            systemListViewModel = systemListViewModel,
            gameListViewModel = gameListViewModel,
            isSelectHeld = { dialogHandler.selectHeld },
            onRescanSystemList = rescanSystemListLambda,
            onScanResumableGames = scanResumableGamesLambda,
            onLaunchSelected = { item, resume -> launchSelected(item, resume) },
            onRecordRecentlyPlayedByPath = { path -> recordRecentlyPlayedByPath(path) },
            onSetPendingRecentlyPlayedReorder = { value -> nav.pendingRecentlyPlayedReorder = value },
            onBuildContextOptions = { item, glState -> dialogHandler.buildGameContextOptions(item, glState) },
        )

        val settingsHandler = dev.cannoli.scorza.input.screen.SettingsInputHandler(
            nav = nav,
            ioScope = ioScope,
            settings = settings,
            platformConfig = platformConfig,
            installedCoreService = installedCoreService,
            profileManager = profileManager,
            globalOverrides = globalOverrides,
            appsRepository = appsRepository,
            setupCoordinator = setupCoordinator,
            inputTesterController = inputTesterController,
            updateManager = updateManager,
            settingsViewModel = settingsViewModel,
            onRescanSystemList = rescanSystemListLambda,
            onInvalidateAllLibraryCaches = invalidateAllLibraryCachesLambda,
            onOpenColorPicker = openColorPickerLambda,
            onStartRaLogin = startRaLogin,
            context = this,
        )

        val setupHandler = dev.cannoli.scorza.input.screen.SetupInputHandler(
            nav = nav,
            settings = settings,
            setupCoordinator = setupCoordinator,
            onInitializeApp = { initializeApp() },
            onFinishAffinity = { finishAffinity() },
            onStartInstalling = { targetPath -> startInstalling(targetPath) },
        )

        val inputTesterHandler = dev.cannoli.scorza.input.screen.InputTesterInputHandler(
            nav = nav,
            controller = inputTesterController,
        )

        fun makeScrollListHandler(screen: LauncherScreen): dev.cannoli.scorza.input.ScreenInputHandler {
            return when (screen) {
                is LauncherScreen.CoreMapping -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.mappings.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.CoreMapping)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CoreMapping)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.CoreMapping ?: return@ScrollListInputHandler
                        s.mappings.getOrNull(s.selectedIndex)?.let { entry ->
                            val bundledCoresDir = LaunchManager.extractBundledCores(this@MainActivity)
                            val options = platformConfig.getCorePickerOptions(
                                entry.tag, packageManager,
                                installedRaCores = installedCoreService.installedCores,
                                embeddedCoresDir = bundledCoresDir,
                                unresponsivePackages = installedCoreService.unresponsivePackages
                            )
                            val currentCore = platformConfig.getCoreMapping(entry.tag)
                            val currentApp = platformConfig.getAppPackage(entry.tag)
                            val currentRunner = entry.runnerLabel
                            val selectedIdx = if ((currentRunner == "App" || currentRunner == "Standalone") && currentApp != null) {
                                options.indexOfFirst { it.appPackage == currentApp }.coerceAtLeast(0)
                            } else {
                                options.indexOfFirst { it.coreId == currentCore && it.runnerLabel == currentRunner }
                                    .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                            }
                            nav.push(LauncherScreen.CorePicker(
                                tag = entry.tag,
                                platformName = entry.platformName,
                                cores = options,
                                selectedIndex = selectedIdx,
                                activeIndex = selectedIdx
                            ))
                        }
                    },
                    onBack = {
                        platformConfig.saveCoreMappings()
                        nav.pop()
                    },
                    onStart = {
                        val s = nav.currentScreen as? LauncherScreen.CoreMapping ?: return@ScrollListInputHandler
                        val newFilter = (s.filter + 1) % 4
                        nav.replaceTop(s.copy(
                            mappings = filterCoreMappings(s.allMappings, newFilter),
                            filter = newFilter, selectedIndex = 0, scrollTarget = 0
                        ))
                    },
                )
                is LauncherScreen.CorePicker -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.cores.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.CorePicker)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CorePicker)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.CorePicker ?: return@ScrollListInputHandler
                        dialogHandler.onCorePickerConfirm(s)
                    },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.CorePicker ?: run { nav.pop(); return@ScrollListInputHandler }
                        nav.pop()
                        if (s.gamePath != null) {
                            dialogHandler.restoreContextMenu()
                        } else {
                            val cm = nav.screenStack.lastOrNull()
                            if (cm is LauncherScreen.CoreMapping) {
                                val all = platformConfig.getDetailedMappings(packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(this@MainActivity), installedCoreService.unresponsivePackages)
                                val filtered = filterCoreMappings(all, cm.filter)
                                val idx = filtered.indexOfFirst { it.tag == s.tag }.coerceAtLeast(0)
                                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                            }
                        }
                    },
                )
                is LauncherScreen.ColorList -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.colors.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.ColorList)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ColorList)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.ColorList ?: return@ScrollListInputHandler
                        s.colors.getOrNull(s.selectedIndex)?.let { entry -> openColorPicker(entry.key) }
                    },
                    onBack = { nav.pop() },
                )
                is LauncherScreen.CollectionPicker -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.collections.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.CollectionPicker)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CollectionPicker)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: return@ScrollListInputHandler
                        if (s.collections.isNotEmpty()) {
                            val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                            nav.replaceTop(s.copy(checkedIndices = newChecked))
                        }
                    },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: run { nav.pop(); return@ScrollListInputHandler }
                        dialogHandler.onCollectionPickerConfirm(s)
                    },
                    onStart = {
                        val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: return@ScrollListInputHandler
                        nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = s.gamePaths)
                    },
                )
                is LauncherScreen.ChildPicker -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.collections.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.ChildPicker)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ChildPicker)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.ChildPicker ?: return@ScrollListInputHandler
                        if (s.collections.isNotEmpty()) {
                            val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                            nav.replaceTop(s.copy(checkedIndices = newChecked))
                        }
                    },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.ChildPicker ?: run { nav.pop(); return@ScrollListInputHandler }
                        dialogHandler.onChildPickerConfirm(s)
                    },
                )
                is LauncherScreen.AppPicker -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.apps.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.AppPicker)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.AppPicker)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.AppPicker ?: return@ScrollListInputHandler
                        val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                        nav.replaceTop(s.copy(checkedIndices = newChecked))
                    },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.AppPicker ?: run { nav.pop(); return@ScrollListInputHandler }
                        settingsHandler.confirmAppPicker(s)
                    },
                )
                is LauncherScreen.ProfileList -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { screen.profiles.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.ProfileList)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ProfileList)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.ProfileList ?: return@ScrollListInputHandler
                        val name = s.profiles.getOrNull(s.selectedIndex)
                        if (name != null) {
                            nav.push(LauncherScreen.ControlBinding(controls = profileManager.readControls(name), profileName = name))
                        }
                    },
                    onBack = { nav.pop() },
                    onStart = {
                        val s = nav.currentScreen as? LauncherScreen.ProfileList ?: return@ScrollListInputHandler
                        val name = s.profiles.getOrNull(s.selectedIndex)
                        if (name != null && !dev.cannoli.scorza.input.ProfileManager.isProtected(name)) {
                            nav.dialogState.value = DialogState.ContextMenu(gameName = name, options = listOf("Rename", "Delete"))
                        }
                    },
                )
                is LauncherScreen.ControlBinding -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { controlButtonCount },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.ControlBinding)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ControlBinding)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = { bindingController.startControlListening() },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.ControlBinding ?: run { nav.pop(); return@ScrollListInputHandler }
                        profileManager.saveControls(s.profileName, s.controls)
                        nav.pop()
                        val prev = nav.screenStack.lastOrNull()
                        if (prev is LauncherScreen.ProfileList) {
                            nav.screenStack[nav.screenStack.lastIndex] = prev.copy(profiles = profileManager.listProfiles())
                        }
                    },
                    onStart = {
                        val s = nav.currentScreen as? LauncherScreen.ControlBinding ?: return@ScrollListInputHandler
                        if (s.listeningIndex < 0) {
                            val btn = controlButtons.getOrNull(s.selectedIndex)
                            if (btn != null && btn.prefKey != "btn_menu") {
                                val currentKeyCode = s.controls[btn.prefKey] ?: btn.defaultKeyCode
                                if (currentKeyCode != LibretroInput.UNMAPPED) {
                                    nav.replaceTop(s.copy(controls = s.controls + (btn.prefKey to LibretroInput.UNMAPPED)))
                                }
                            }
                        }
                    },
                )
                is LauncherScreen.ShortcutBinding -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { dev.cannoli.igm.ShortcutAction.entries.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.ShortcutBinding)?.selectedIndex ?: 0 },
                    onMove = { idx ->
                        val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@ScrollListInputHandler
                        if (!s.listening) nav.replaceTop(s.copy(selectedIndex = idx))
                    },
                    onConfirm = {
                        val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@ScrollListInputHandler
                        if (!s.listening) {
                            nav.replaceTop(s.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                        }
                    },
                    onBack = {
                        val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: run { nav.pop(); return@ScrollListInputHandler }
                        cancelShortcutListening()
                        globalOverrides.saveShortcuts(s.shortcuts)
                        nav.pop()
                    },
                    onStart = {
                        val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@ScrollListInputHandler
                        if (!s.listening) {
                            dev.cannoli.igm.ShortcutAction.entries.getOrNull(s.selectedIndex)?.let { action ->
                                nav.replaceTop(s.copy(shortcuts = s.shortcuts + (action to emptySet())))
                            }
                        }
                    },
                )
                is LauncherScreen.Credits -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { dev.cannoli.scorza.ui.components.CREDITS.size },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.Credits)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.Credits)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {},
                    onBack = { nav.pop() },
                )
                is LauncherScreen.InstalledCores -> dev.cannoli.scorza.input.screen.ScrollListInputHandler(
                    nav = nav,
                    itemCount = { (nav.currentScreen as? LauncherScreen.InstalledCores)?.cores?.size ?: 0 },
                    selectedIndex = { (nav.currentScreen as? LauncherScreen.InstalledCores)?.selectedIndex ?: 0 },
                    onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.InstalledCores)?.copy(selectedIndex = idx) ?: return@ScrollListInputHandler) },
                    onConfirm = {},
                    onBack = {
                        unregisterCoreQueryReceiver()
                        nav.pop()
                    },
                )
                else -> object : dev.cannoli.scorza.input.ScreenInputHandler {}
            }
        }

        router = dev.cannoli.scorza.input.InputRouter(
            nav = nav,
            dialogHandler = dialogHandler,
            systemListHandler = systemListHandler,
            gameListHandler = gameListHandler,
            settingsHandler = settingsHandler,
            setupHandler = setupHandler,
            inputTesterHandler = inputTesterHandler,
            scrollListHandlerFor = { screen -> makeScrollListHandler(screen) },
        )
        router.wire(inputHandler)
    }

    private fun setupWireInput() {
        // Minimal wiring for the setup/permission flow before the router is initialized.
        // The router.wire() call in finishInitializeApp() overwrites these once ready.
        inputHandler.onUp = {
            when (val screen = preInitScreenStack.lastOrNull()) {
                is LauncherScreen.Setup -> preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(selectedIndex = (screen.selectedIndex - 1).coerceAtLeast(0))
                is LauncherScreen.DirectoryBrowser -> {
                    val hasSelect = screen.currentPath != "/storage/"
                    val count = screen.entries.size + if (hasSelect) 1 else 0
                    if (count > 0) preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, count))
                }
                else -> {}
            }
        }
        inputHandler.onDown = {
            when (val screen = preInitScreenStack.lastOrNull()) {
                is LauncherScreen.Setup -> {
                    val maxIndex = if (screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom") 1 else 0
                    preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(selectedIndex = (screen.selectedIndex + 1).coerceAtMost(maxIndex))
                }
                is LauncherScreen.DirectoryBrowser -> {
                    val hasSelect = screen.currentPath != "/storage/"
                    val count = screen.entries.size + if (hasSelect) 1 else 0
                    if (count > 0) preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, count))
                }
                else -> {}
            }
        }
        inputHandler.onLeft = {
            (preInitScreenStack.lastOrNull() as? LauncherScreen.Setup)?.let { screen ->
                if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                    preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(
                        volumeIndex = (screen.volumeIndex - 1 + screen.volumes.size) % screen.volumes.size,
                        customPath = null
                    )
                }
            }
        }
        inputHandler.onRight = {
            (preInitScreenStack.lastOrNull() as? LauncherScreen.Setup)?.let { screen ->
                if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                    preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(
                        volumeIndex = (screen.volumeIndex + 1) % screen.volumes.size,
                        customPath = null
                    )
                }
            }
        }
        inputHandler.onConfirm = {
            when (val screen = preInitScreenStack.lastOrNull()) {
                is LauncherScreen.Setup -> {
                    val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                    val folderIndex = if (isCustom) 1 else -1
                    if (screen.selectedIndex == folderIndex) {
                        pushDirectoryBrowser(BrowsePurpose.SETUP, "/storage/")
                    }
                }
                is LauncherScreen.Installing -> {
                    if (screen.finished) {
                        settings.sdCardRoot = screen.targetPath
                        settings.setupCompleted = true
                        initializeApp()
                    }
                }
                is LauncherScreen.DirectoryBrowser -> {
                    val hasSelect = screen.currentPath != "/storage/"
                    val selectIndex = if (hasSelect) 0 else -1
                    if (screen.selectedIndex == selectIndex) {
                        onDirectoryBrowserResult(screen.purpose, screen.currentPath)
                        preInitScreenStack.removeAt(preInitScreenStack.lastIndex)
                    } else {
                        val entryIdx = screen.selectedIndex - if (hasSelect) 1 else 0
                        val folderName = screen.entries[entryIdx]
                        val newPath = setupCoordinator.resolveDirectoryEntry(screen.currentPath, folderName)
                        val newEntries = setupCoordinator.listDirectories(newPath)
                        preInitScreenStack[preInitScreenStack.lastIndex] = LauncherScreen.DirectoryBrowser(
                            purpose = screen.purpose,
                            currentPath = newPath,
                            entries = newEntries
                        )
                    }
                }
                else -> {}
            }
        }
        inputHandler.onBack = {
            when (val screen = preInitScreenStack.lastOrNull()) {
                is LauncherScreen.DirectoryBrowser -> {
                    val parent = setupCoordinator.parentDirectory(screen.currentPath)
                    if (parent != null) {
                        val newEntries = setupCoordinator.listDirectories(parent)
                        preInitScreenStack[preInitScreenStack.lastIndex] = LauncherScreen.DirectoryBrowser(
                            purpose = screen.purpose,
                            currentPath = parent,
                            entries = newEntries
                        )
                    }
                }
                is LauncherScreen.Setup -> finishAffinity()
                else -> {}
            }
        }
        inputHandler.onStart = {
            (preInitScreenStack.lastOrNull() as? LauncherScreen.Setup)?.let { screen ->
                val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                val continueEnabled = !isCustom || screen.customPath != null
                if (continueEnabled) {
                    val targetPath = if (isCustom) screen.customPath!! else screen.volumes[screen.volumeIndex].second + "Cannoli/"
                    preInitScreenStack[preInitScreenStack.lastIndex] = LauncherScreen.Installing(targetPath = targetPath)
                    startInstalling(targetPath)
                }
            }
        }
    }

    private fun wrapIndex(current: Int, delta: Int, size: Int): Int =
        if (size == 0) 0 else (current + delta).mod(size)


    private fun hasActiveVpn(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun rescanSystemList() {
        val fghStem = validateFghStem()
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            showEmpty = settings.showEmpty,
            contentMode = settings.contentMode,
            fghCollectionStem = fghStem,
            toolsName = settings.toolsName,
            portsName = settings.portsName,
            onReady = {
                if (fghStem != null) {
                    scanResumableGames()
                }
            }
        )
    }

    private fun validateFghStem(): String? {
        if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) return null
        val all = collectionsRepository.all().filter { it.type == CollectionType.STANDARD }
        val stem = settings.fghCollectionStem
        if (stem != null && all.any { it.displayName == stem }) return stem
        val fallback = all.firstOrNull()?.displayName
        settings.fghCollectionStem = fallback
        return fallback
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> R.string.setting_color_accent
            "color_highlight" -> R.string.setting_color_highlight
            "color_highlight_text" -> R.string.setting_color_highlight_text
            "color_text" -> R.string.setting_color_text
            "color_title" -> R.string.setting_color_title
            else -> return ""
        }
        return getString(labelRes)
    }

    private fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        nav.dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    private fun scanResumableGames() {
        val gameListRoms = gameListViewModel.state.value.items
            .filterIsInstance<dev.cannoli.scorza.model.ListItem.RomItem>()
            .map { it.rom }
        val systemListRoms = systemListViewModel.state.value.items
            .filterIsInstance<SystemListViewModel.ListItem.GameItem>()
            .mapNotNull { (it.item as? dev.cannoli.scorza.model.ListItem.RomItem)?.rom }
        val roms = (gameListRoms + systemListRoms).distinctBy { it.path.absolutePath }
        ioScope.launch {
            val result = launchManager.findResumableRoms(roms)
            withContext(Dispatchers.Main) { if (::nav.isInitialized) nav.resumableGames = result }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun unregisterCoreQueryReceiver() {
        coreQueryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
            coreQueryReceiver = null
        }
    }

    private fun filterCoreMappings(all: List<dev.cannoli.scorza.ui.screens.CoreMappingEntry>, filter: Int): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionLauncher.launch(intent)
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

}
