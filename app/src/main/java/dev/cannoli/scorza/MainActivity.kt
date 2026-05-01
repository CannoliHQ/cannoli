package dev.cannoli.scorza

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
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
import androidx.compose.runtime.LaunchedEffect
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
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.di.RomDir
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.BindingController
import dev.cannoli.scorza.input.ControllerManager
import dev.cannoli.scorza.input.AndroidGamepadKeyNames
import dev.cannoli.scorza.input.v2.runtime.InputDispatcher
import dev.cannoli.scorza.input.InputRouter
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.scorza.input.v2.runtime.ControllerV2Bridge
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.PermissionScreen
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.updater.UpdateManager
import dev.cannoli.ui.theme.CannoliTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity(), ActivityActions {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var platformConfig: PlatformConfig
    @Inject lateinit var nav: NavigationController
    @Inject lateinit var router: InputRouter
    @Inject lateinit var inputDispatcher: InputDispatcher
    @Inject lateinit var controllerManager: ControllerManager
    @Inject lateinit var controllerV2Bridge: ControllerV2Bridge
    @Inject lateinit var portRouter: dev.cannoli.scorza.input.v2.runtime.PortRouter
    @Inject lateinit var activeMappingHolder: dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder
    @Inject lateinit var bindingController: BindingController
    @Inject lateinit var inputTesterController: InputTesterController
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var setupCoordinator: SetupCoordinator
    @Inject lateinit var launchManager: LaunchManager
    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var installedCoreService: InstalledCoreService
    @Inject lateinit var romsRepository: RomsRepository
    @Inject lateinit var romScanner: RomScanner
    @Inject lateinit var collectionsRepository: CollectionsRepository
    @Inject lateinit var cannoliDatabase: CannoliDatabase
    @Inject lateinit var launcherActions: LauncherActions
    @Inject lateinit var systemListViewModel: SystemListViewModel
    @Inject lateinit var gameListViewModel: GameListViewModel
    @Inject lateinit var settingsViewModel: SettingsViewModel
    @Inject lateinit var inputTesterViewModel: InputTesterViewModel
    @Inject lateinit var controllersViewModel: dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
    @Inject lateinit var editButtonsController: dev.cannoli.scorza.input.EditButtonsController
    @Inject lateinit var mappingRepository: dev.cannoli.scorza.input.v2.repo.MappingRepository
    @Inject @RomDir lateinit var romDir: File
    @Inject @IoScope lateinit var ioScope: CoroutineScope

    private val isTv: Boolean by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    private val preInitScreenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)

    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var permissionGranted by mutableStateOf(false)
    private var coldStart = true

    private fun cancelShortcutListening() = bindingController.cancelShortcutListening()

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

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        dev.cannoli.scorza.util.InputLog.write("BLUETOOTH_CONNECT permission ${if (granted) "granted" else "denied"}")
        if (granted) {
            controllerV2Bridge.settleNow()
            controllersViewModel.refreshFromRouter()
        }
    }

    private fun loadLoggingPrefs() {
        dev.cannoli.scorza.util.LoggingPrefs.fileScanner = settings.loggingFileScanner
        dev.cannoli.scorza.util.LoggingPrefs.romScan = settings.loggingRomScan
        dev.cannoli.scorza.util.LoggingPrefs.input = settings.loggingInput
        dev.cannoli.scorza.util.LoggingPrefs.session = settings.loggingSession
    }

    private fun maybeRequestBluetoothConnect() {
        if (Build.VERSION.SDK_INT < 31) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()
        editButtonsController.cancelListening()
        // InputLog (and the runtime logging gates) must be set up BEFORE the bridge starts so
        // the initial-enumeration log lines are captured.
        loadLoggingPrefs()
        if (settings.sdCardRoot.isNotEmpty()) {
            dev.cannoli.scorza.util.InputLog.init(settings.sdCardRoot)
        }
        maybeRequestBluetoothConnect()
        controllerV2Bridge.start(this)

        setupWireInput()

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
                        val updateInfo = updateManager.updateAvailable.collectAsState().value
                        val dlProgress = updateManager.downloadProgress.collectAsState().value
                        val dlError = updateManager.downloadError.collectAsState().value
                        val navScreen = nav.currentScreen
                        val navDialogState = nav.dialogState
                        val navResumableGames = nav.resumableGames
                        val navOsdMessage = nav.osdMessage
                        val activeMapping by activeMappingHolder.active.collectAsState()
                        LaunchedEffect(navScreen) {
                            if (navScreen is LauncherScreen.Controllers) {
                                controllersViewModel.refreshFromRouter()
                            }
                        }
                        AppNavGraph(
                            currentScreen = navScreen,
                            systemListViewModel = systemListViewModel,
                            gameListViewModel = gameListViewModel,
                            inputTesterViewModel = inputTesterViewModel,
                            onExitInputTester = {
                                inputTesterController.exit()
                                if (nav.screenStack.size > 1) nav.screenStack.removeAt(nav.screenStack.lastIndex)
                            },
                            settingsViewModel = settingsViewModel,
                            controllersViewModel = controllersViewModel,
                            dialogState = navDialogState,
                            onVisibleRangeChanged = { first, count, full ->
                                nav.currentFirstVisible = first
                                if (full) nav.currentPageSize = count
                            },
                            resumableGames = navResumableGames,
                            updateAvailable = updateInfo != null,
                            downloadProgress = dlProgress ?: 0f,
                            downloadError = dlError,
                            osdMessage = navOsdMessage,
                            activeMapping = activeMapping,
                            mappingRepository = mappingRepository,
                            editButtonsController = editButtonsController,
                            nav = nav,
                        )
                    }
                }
            }
        }
    }

    private fun afterPermissionGranted() {
        if (settings.setupCompleted) {
            initializeApp()
        } else if (dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).settingsJson.exists()) {
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
                val stack = preInitScreenStack
                stack.clear()
                stack.add(LauncherScreen.Setup(volumes = volumes))
            }
        }
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        preInitScreenStack.add(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun onDirectoryBrowserResult(purpose: BrowsePurpose, path: String) {
        val resolved = if (setupCoordinator.isVolumeRoot(path)) path + "Cannoli/" else path
        when (purpose) {
            BrowsePurpose.SD_ROOT -> {
                settings.sdCardRoot = resolved
                nav.dialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.ROM_DIRECTORY -> {
                settings.romDirectory = resolved
                launcherActions.invalidateAllLibraryCaches()
                nav.dialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.SETUP -> {
                val stack = preInitScreenStack
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
                val stack = preInitScreenStack
                val screen = stack.lastOrNull() as? LauncherScreen.Installing ?: return@startInstalling
                stack[stack.lastIndex] = screen.copy(progress = progress, statusLabel = label)
            },
            onFinished = { _ ->
                val stack = preInitScreenStack
                val screen = stack.lastOrNull() as? LauncherScreen.Installing ?: return@startInstalling
                stack[stack.lastIndex] = screen.copy(
                    progress = 1f,
                    statusLabel = "Cannoli is now ready to be garnished!",
                    finished = true
                )
            },
        )
    }

    private fun buildConnectedRows(): List<dev.cannoli.scorza.ui.viewmodel.ConnectedRow> {
        val routes = portRouter.routes.value
        val deviceIds = android.view.InputDevice.getDeviceIds().toList()
        return deviceIds.mapNotNull { id ->
            val device = android.view.InputDevice.getDevice(id) ?: return@mapNotNull null
            val mapping = portRouter.mappingFor(id) ?: return@mapNotNull null
            dev.cannoli.scorza.ui.viewmodel.ConnectedRow(
                androidDeviceId = id,
                mapping = mapping,
                port = routes[id],
                isBuiltIn = device.vendorId == 0 && device.productId == 0,
            )
        }
    }

    private val osdHandler = Handler(Looper.getMainLooper())
    private val clearOsd = Runnable { nav.osdMessage = null }

    private fun registerControllerOsd() {
        controllerV2Bridge.onDeviceAdded = { device ->
            val port = portRouter.portFor(device.androidDeviceId)
            if (port != null) {
                val name = device.name.ifEmpty { "Controller" }
                osdHandler.removeCallbacks(clearOsd)
                nav.osdMessage = "P${port + 1}: $name"
                osdHandler.postDelayed(clearOsd, 3000)
            }
        }
        controllerV2Bridge.onDeviceRemoved = { departed ->
            osdHandler.removeCallbacks(clearOsd)
            val portLabel = departed.port?.let { "P${it + 1}: " } ?: ""
            nav.osdMessage = "$portLabel${departed.displayName} disconnected"
            osdHandler.postDelayed(clearOsd, 3000)
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        registerControllerOsd()
        if (!permissionGranted && hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
            return
        }
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        launchManager.launching = false
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, opts)
            return
        }
        settings.reload()
        settingsViewModel.load()
        val activeDialogState = nav.dialogState
        if (activeDialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
            activeDialogState.value = DialogState.None
        }
        if (permissionGranted) {
            rescanSystemList()
            val activeScreen = nav.currentScreen
            if (activeScreen is LauncherScreen.GameList) {
                gameListViewModel.reload { launcherActions.scanResumableGames() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        controllerV2Bridge.onDeviceAdded = null
        controllerV2Bridge.onDeviceRemoved = null
        if (nav.pendingRecentlyPlayedReorder) {
            nav.pendingRecentlyPlayedReorder = false
            gameListViewModel.moveSelectedToTop()
        }
    }

    override fun onDestroy() {
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .unregisterInputDeviceListener(controllerManager)
        controllerV2Bridge.onDeviceAdded = null
        controllerV2Bridge.onDeviceRemoved = null
        controllerV2Bridge.stop(this)
        super.onDestroy()
        unregisterCoreQueryReceiver()
        settings.shutdown()
        systemListViewModel.close()
        gameListViewModel.close()
        dev.cannoli.scorza.server.KitchenManager.stop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cs = nav.currentScreen
        if (cs is LauncherScreen.EditButtons && editButtonsController.isListening
            && event.action == KeyEvent.ACTION_DOWN) {
            editButtonsController.captureRawKeyEvent(event.keyCode)
            return true
        }
        if (!AndroidGamepadKeyNames.isGamepadEvent(event)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val currentScreenForKey = nav.currentScreen
                    if (currentScreenForKey is LauncherScreen.InputTester) {
                        inputTesterController.dispatchKey(event, down = event.action == KeyEvent.ACTION_DOWN)
                    } else if (isTv && event.action == KeyEvent.ACTION_DOWN && permissionGranted) {
                        inputDispatcher.onBack()
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
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = true)
            return true
        }
        if (bindingController.handleKeyDown(keyCode)) {
            return true
        }
        if (event.repeatCount > 0 && currentScreenForKey is LauncherScreen.ShortcutBinding && !currentScreenForKey.listening) {
            return true
        }
        nav.lastKeyRepeatCount = event.repeatCount
        if (isTv && !AndroidGamepadKeyNames.isGamepadEvent(event)) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { inputDispatcher.onWest(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { inputDispatcher.onNorth(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { inputDispatcher.onStart(); return true }
            }
        }
        if (inputDispatcher.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = false)
            return true
        }
        if (inputDispatcher.handleKeyEvent(event)) {
            return true
        }
        if (currentScreenForKey is LauncherScreen.ShortcutBinding && currentScreenForKey.listening && currentScreenForKey.heldKeys.contains(keyCode)) {
            cancelShortcutListening()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!permissionGranted) return super.onGenericMotionEvent(event)
        val currentScreenForMotion = nav.currentScreen
        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }
        val handled = inputDispatcher.handleMotionEvent(event)
        updateMenuRepeatFromMotion(event)
        return handled || super.onGenericMotionEvent(event)
    }

    // Auto-repeat for held d-pad / left stick on hat-axis controllers (Android does not synthesize
    // KeyEvent repeats for hat-axis events; we poll the axis and re-fire callbacks ourselves).
    private val menuRepeatHandler = Handler(Looper.getMainLooper())
    private val menuRepeatDelayMs = 400L
    private val menuRepeatIntervalMs = 80L
    private var menuHeldDir = 0
    private val menuRepeatRunnable = object : Runnable {
        override fun run() {
            when (menuHeldDir) {
                1 -> inputDispatcher.onUp()
                2 -> inputDispatcher.onDown()
                3 -> inputDispatcher.onLeft()
                4 -> inputDispatcher.onRight()
            }
            if (menuHeldDir != 0) {
                menuRepeatHandler.postDelayed(this, menuRepeatIntervalMs)
            }
        }
    }

    private fun updateMenuRepeatFromMotion(event: MotionEvent) {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        val x = if (kotlin.math.abs(hatX) > 0.5f) hatX else stickX
        val y = if (kotlin.math.abs(hatY) > 0.5f) hatY else stickY
        val newDir = when {
            y < -0.5f -> 1
            y > 0.5f -> 2
            x < -0.5f -> 3
            x > 0.5f -> 4
            else -> 0
        }
        if (newDir != menuHeldDir) {
            menuRepeatHandler.removeCallbacks(menuRepeatRunnable)
            menuHeldDir = newDir
            if (newDir != 0) menuRepeatHandler.postDelayed(menuRepeatRunnable, menuRepeatDelayMs)
        }
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
            val currentScreenForTrigger = nav.currentScreen
            if (currentScreenForTrigger is LauncherScreen.ShortcutBinding && currentScreenForTrigger.listening && currentScreenForTrigger.heldKeys.contains(keyCode)) {
                cancelShortcutListening()
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val csForListen = nav.currentScreen
        if (csForListen is LauncherScreen.EditButtons && editButtonsController.isListening) {
            val axes = listOf(0, 1, 11, 14, 15, 16, 17, 18, 22, 23)
            val axisValues = axes.associateWith { event.getAxisValue(it) }
            editButtonsController.captureRawAxisEvent(axisValues)
            return true
        }
        if (csForListen is LauncherScreen.EditButtons) {
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            if (kotlin.math.abs(hatX) > 0.3f || kotlin.math.abs(hatY) > 0.3f) {
                dev.cannoli.scorza.util.DebugLog.write("[edit-nav] motion hatX=$hatX hatY=$hatY")
            }
        }
        val source = event.source
        val isJoystick =
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        val currentScreenForMotion = nav.currentScreen
        if (currentScreenForMotion is LauncherScreen.ShortcutBinding) {
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
        loadLoggingPrefs()
        dev.cannoli.scorza.util.DebugLog.init(root.absolutePath)
        dev.cannoli.scorza.util.ScanLog.init(root.absolutePath)
        dev.cannoli.scorza.util.InputLog.init(root.absolutePath)
        launchManager.syncRetroArchAssets(root)
        launchManager.syncRetroArchConfig(root)
        ioScope.launch { dev.cannoli.scorza.util.DirectoryLayout.ensure(root, romDir, assets, platformConfig) }
        runImporterThenContinue(root, romDir)
    }

    private fun runImporterThenContinue(root: File, romDirectory: File) {
        val importer = dev.cannoli.scorza.db.importer.Importer(
            cannoliRoot = root,
            romDirectory = romDirectory,
            db = cannoliDatabase,
            platformConfig = platformConfig,
            romScanner = romScanner,
            onProgress = dev.cannoli.scorza.db.importer.ImportProgress { progress, label ->
                runOnUiThread {
                    val stack = preInitScreenStack
                    val top = stack.lastOrNull()
                    if (top is LauncherScreen.Housekeeping &&
                        top.kind == dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION) {
                        stack[stack.lastIndex] = top.copy(progress = progress, statusLabel = label)
                    }
                }
            },
        )

        preInitScreenStack.add(
            LauncherScreen.Housekeeping(
                kind = dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION,
                progress = 0f,
                statusLabel = "Preparing",
            )
        )

        ioScope.launch {
            val result = importer.run()
            withContext(Dispatchers.Main) {
                val stack = preInitScreenStack
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
        if (preInitScreenStack.firstOrNull() !is LauncherScreen.SystemList) {
            preInitScreenStack.clear()
            preInitScreenStack.add(LauncherScreen.SystemList)
        }

        val root = File(settings.sdCardRoot)

        ioScope.launch {
            installedCoreService.queryAllPackages()
            platformConfig.purgeStaleRaMappings(installedCoreService.installedCores)
        }

        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        settingsViewModel.reinitialize(root, packageManager, packageName, collectionsRepository)

        controllerManager.loadBlacklist(this)
        controllerManager.initialize()
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .registerInputDeviceListener(controllerManager,
                Handler(android.os.Looper.getMainLooper())
            )

        profileManager.reinitialize(settings.sdCardRoot)

        if (updateManager.shouldAutoCheck()) {
            ioScope.launch { updateManager.checkForUpdate() }
        }

        ioScope.launch {
            updateManager.updateAvailable.collect { info ->
                settingsViewModel.updateInfo = info
            }
        }

        router.cancelShortcutListening = { cancelShortcutListening() }
        router.unregisterCoreQueryReceiver = { unregisterCoreQueryReceiver() }
        router.wire(inputDispatcher)

        nav.screenStack.clear()
        nav.screenStack.add(LauncherScreen.SystemList)

        launcherActions.rescanSystemList()

        val quickResume = dev.cannoli.scorza.config.CannoliPaths(root).quickResumeFile
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
                            launcherActions.recordRecentlyPlayedByPath(romFile.absolutePath)
                        }
                    }
                }
            }
        }
    }

    private fun rescanSystemList() {
        launcherActions.rescanSystemList()
    }

    private fun wrapIndex(current: Int, delta: Int, size: Int): Int =
        if (size == 0) 0 else (current + delta).mod(size)

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

    private fun setupWireInput() {
        inputDispatcher.onUp = {
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
        inputDispatcher.onDown = {
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
        inputDispatcher.onLeft = {
            (preInitScreenStack.lastOrNull() as? LauncherScreen.Setup)?.let { screen ->
                if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                    preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(
                        volumeIndex = (screen.volumeIndex - 1 + screen.volumes.size) % screen.volumes.size,
                        customPath = null
                    )
                }
            }
        }
        inputDispatcher.onRight = {
            (preInitScreenStack.lastOrNull() as? LauncherScreen.Setup)?.let { screen ->
                if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                    preInitScreenStack[preInitScreenStack.lastIndex] = screen.copy(
                        volumeIndex = (screen.volumeIndex + 1) % screen.volumes.size,
                        customPath = null
                    )
                }
            }
        }
        inputDispatcher.onConfirm = {
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
        inputDispatcher.onBack = {
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
        inputDispatcher.onStart = {
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

    override fun finishAffinity() = super.finishAffinity()

    override fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        startActivity(intent, opts)
        Runtime.getRuntime().exit(0)
    }

    override fun startRaLogin(username: String, password: String) {
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

}
