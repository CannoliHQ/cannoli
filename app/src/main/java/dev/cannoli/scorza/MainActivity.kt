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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.boot.BootSequencer
import dev.cannoli.scorza.boot.BootState
import dev.cannoli.scorza.boot.StartStorageDependentHolder
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.AppFonts
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.BindingController
import dev.cannoli.scorza.input.AndroidGamepadKeyNames
import dev.cannoli.scorza.input.v2.runtime.InputDispatcher
import dev.cannoli.scorza.input.InputRouter
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.v2.runtime.ControllerV2Bridge
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.BootErrorScreen
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.updater.UpdateManager
import dev.cannoli.ui.theme.CannoliTheme
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MainActivity : ComponentActivity(), ActivityActions {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var platformConfig: Provider<PlatformConfig>
    @Inject lateinit var nav: NavigationController
    @Inject lateinit var router: InputRouter
    @Inject lateinit var onboardingHandler: dev.cannoli.scorza.input.screen.OnboardingInputHandler
    @Inject lateinit var inputDispatcher: InputDispatcher
    @Inject lateinit var controllerV2Bridge: ControllerV2Bridge
    @Inject lateinit var portRouter: dev.cannoli.scorza.input.v2.runtime.PortRouter
    @Inject lateinit var activeMappingHolder: dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder
    @Inject lateinit var bindingController: BindingController
    @Inject lateinit var osdController: dev.cannoli.ui.components.OsdController
    @Inject lateinit var inputTesterController: InputTesterController
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var setupCoordinator: SetupCoordinator
    @Inject lateinit var launchManager: Provider<LaunchManager>
    @Inject lateinit var installedCoreService: Provider<InstalledCoreService>
    @Inject lateinit var romsRepository: Provider<RomsRepository>
    @Inject lateinit var romScanner: Provider<RomScanner>
    @Inject lateinit var collectionsRepository: Provider<CollectionsRepository>
    @Inject lateinit var cannoliDatabase: Provider<CannoliDatabase>
    @Inject lateinit var launcherActions: Provider<LauncherActions>
    @Inject lateinit var systemListViewModel: Provider<SystemListViewModel>
    @Inject lateinit var gameListViewModel: Provider<GameListViewModel>
    @Inject lateinit var settingsViewModel: Provider<SettingsViewModel>
    @Inject lateinit var inputTesterViewModel: Provider<InputTesterViewModel>
    @Inject lateinit var controllersViewModel: Provider<dev.cannoli.scorza.ui.viewmodel.ControllersViewModel>
    @Inject lateinit var editButtonsController: dev.cannoli.scorza.input.EditButtonsController
    @Inject lateinit var mappingRepository: Provider<dev.cannoli.scorza.input.v2.repo.MappingRepository>
    @Inject lateinit var bootSequencer: BootSequencer
    @Inject lateinit var startStorageDependentHolder: StartStorageDependentHolder
    @Inject lateinit var appFonts: AppFonts
    @Inject lateinit var controllerBlacklist: dev.cannoli.scorza.input.ControllerBlacklist

    private val isTv: Boolean by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    private val isReady: Boolean get() = bootSequencer.state.value is BootState.Ready

    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var coldStart = true

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bootSequencer.onStoragePermissionResult()
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bootSequencer.onStoragePermissionResult()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        dev.cannoli.scorza.util.InputLog.write("BLUETOOTH_CONNECT permission ${if (granted) "granted" else "denied"}")
        if (granted) {
            controllerV2Bridge.startBtTracker()
            controllerV2Bridge.settleNow()
        }
        bootSequencer.onBluetoothPermissionResult(granted)
    }

    private fun loadLoggingPrefs() {
        dev.cannoli.scorza.util.LoggingPrefs.romScan = settings.loggingRomScan
        dev.cannoli.scorza.util.LoggingPrefs.input = settings.loggingInput
        dev.cannoli.scorza.util.LoggingPrefs.session = settings.loggingSession
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
        loadLoggingPrefs()

        startStorageDependentHolder.register { startStorageDependent() }
        onboardingHandler.onStartInstalling = { targetPath -> startInstalling(targetPath) }
        onboardingHandler.onInstallFinished = { bootSequencer.onInstallFinished() }
        onboardingHandler.onRequestPermission = { perm ->
            when (perm) {
                dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE -> requestStoragePermission()
                dev.cannoli.scorza.navigation.OnboardingPermission.BLUETOOTH ->
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        onboardingHandler.onContinue = { bootSequencer.confirmPermissions() }
        router.unregisterCoreQueryReceiver = { unregisterCoreQueryReceiver() }
        router.wire(inputDispatcher)

        controllerBlacklist.load(this)
        val btReady = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        controllerV2Bridge.start(this, includeBtTracker = btReady)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        bootSequencer.advance()

        setContent {
            val boot by bootSequencer.state.collectAsState()

            val themeFont = if (boot is BootState.Ready) {
                settingsViewModel.get().appSettings.collectAsState().value.fontFamily
            } else {
                appFonts.mplus1Code
            }
            CannoliTheme(fontFamily = themeFont) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val s = boot) {
                        is BootState.Resolving -> Box(modifier = Modifier.fillMaxSize()) {}
                        is BootState.NeedsPermission -> {
                            LaunchedEffect(s.storageGranted, s.bluetoothGranted) {
                                val perms = buildList {
                                    add(dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                    if (Build.VERSION.SDK_INT >= 31) add(dev.cannoli.scorza.navigation.OnboardingPermission.BLUETOOTH)
                                }
                                val granted = buildSet {
                                    if (s.storageGranted) add(dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                    if (s.bluetoothGranted) add(dev.cannoli.scorza.navigation.OnboardingPermission.BLUETOOTH)
                                }
                                val top = nav.currentScreen
                                if (top is LauncherScreen.OnboardingPermissions) {
                                    nav.replaceTop(top.copy(permissions = perms, granted = granted))
                                } else {
                                    nav.screenStack.clear()
                                    nav.screenStack.add(LauncherScreen.OnboardingPermissions(perms, granted))
                                }
                            }
                            ReadyNavGraph()
                        }
                        is BootState.NeedsSetup -> {
                            LaunchedEffect(s.volumes) {
                                val top = nav.currentScreen
                                if (top !is LauncherScreen.Setup
                                    && top !is LauncherScreen.Installing
                                    && top !is LauncherScreen.DirectoryBrowser) {
                                    nav.screenStack.clear()
                                    nav.screenStack.add(LauncherScreen.Setup(volumes = s.volumes))
                                }
                            }
                            ReadyNavGraph()
                        }
                        is BootState.Initializing -> {
                            val kind = when (s.phase) {
                                dev.cannoli.scorza.boot.BootPhase.IMPORT ->
                                    dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION
                                dev.cannoli.scorza.boot.BootPhase.LIBRARY_REFRESH ->
                                    dev.cannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH
                            }
                            dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                                kind = kind,
                                progress = s.progress,
                                statusLabel = s.label,
                            )
                        }
                        is BootState.Error -> BootErrorScreen(message = s.message)
                        is BootState.Ready -> ReadyNavGraph()
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReadyNavGraph() {
        val svm = settingsViewModel.get()
        val slvm = systemListViewModel.get()
        val glvm = gameListViewModel.get()
        val itvm = inputTesterViewModel.get()
        val cvm = controllersViewModel.get()
        val updateInfo = updateManager.updateAvailable.collectAsState().value
        val dlProgress = updateManager.downloadProgress.collectAsState().value
        val dlError = updateManager.downloadError.collectAsState().value
        val navScreen = nav.currentScreen
        val navDialogState = nav.dialogState
        val navResumableGames = nav.resumableGames
        val activeMapping by activeMappingHolder.active.collectAsState()
        LaunchedEffect(navScreen) {
        }
        AppNavGraph(
            currentScreen = navScreen,
            systemListViewModel = slvm,
            gameListViewModel = glvm,
            inputTesterViewModel = itvm,
            onExitInputTester = {
                inputTesterController.exit()
                if (nav.screenStack.size > 1) nav.screenStack.removeAt(nav.screenStack.lastIndex)
            },
            settingsViewModel = svm,
            controllersViewModel = cvm,
            dialogState = navDialogState,
            onVisibleRangeChanged = { first, count, full ->
                nav.currentFirstVisible = first
                if (full) nav.currentPageSize = count
            },
            resumableGames = navResumableGames,
            updateAvailable = updateInfo != null,
            downloadProgress = dlProgress ?: 0f,
            downloadError = dlError,
            osdController = osdController,
            activeMapping = activeMapping,
            mappingRepository = mappingRepository.get(),
            editButtonsController = editButtonsController,
            nav = nav,
        )
    }

    /**
     * Re-run the storage-backed parts of input setup once MANAGE_EXTERNAL_STORAGE is available:
     * point the input log at the SD card and re-settle so controllers pick up saved profiles.
     * The controller bridge itself is started in onCreate (before permission) so the onboarding
     * wizard is operable. BootSequencer invokes this once, on the edge into Initializing.
     */
    private fun startStorageDependent() {
        if (settings.sdCardRoot.isNotEmpty()) {
            dev.cannoli.scorza.util.InputLog.init(settings.sdCardRoot)
        }
        controllerV2Bridge.settleNow()
    }

    private fun startInstalling(targetPath: String) {
        setupCoordinator.startInstalling(
            targetPath = targetPath,
            onProgress = { progress, label ->
                val screen = nav.currentScreen as? LauncherScreen.Installing ?: return@startInstalling
                nav.replaceTop(screen.copy(progress = progress, statusLabel = label))
            },
            onFinished = { _ ->
                val screen = nav.currentScreen as? LauncherScreen.Installing ?: return@startInstalling
                nav.replaceTop(screen.copy(
                    progress = 1f,
                    statusLabel = "Cannoli is now ready to be garnished!",
                    finished = true
                ))
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

    private fun registerControllerOsd() {
        controllerV2Bridge.onDeviceAdded = { device ->
            val port = portRouter.portFor(device.androidDeviceId)
            if (port != null) {
                val name = portRouter.mappingForPort(port)?.displayName?.takeIf { it.isNotEmpty() }
                    ?: device.name.ifEmpty { "Controller" }
                osdController.show("P${port + 1}: $name")
            }
        }
        controllerV2Bridge.onDeviceRemoved = { departed ->
            val portLabel = departed.port?.let { "P${it + 1}: " } ?: ""
            osdController.show("$portLabel${departed.displayName} disconnected")
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        registerControllerOsd()
        bootSequencer.advance()
        if (!isReady) return
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        launchManager.get().launching = false
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, opts)
            return
        }
        settings.reload()
        settingsViewModel.get().load()
        val activeDialogState = nav.dialogState
        if (activeDialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
            activeDialogState.value = DialogState.None
        }
        if (!systemListViewModel.get().state.value.isLoading) {
            rescanSystemList()
            val activeScreen = nav.currentScreen
            if (activeScreen is LauncherScreen.GameList) {
                gameListViewModel.get().reload { launcherActions.get().scanResumableGames() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        controllerV2Bridge.onDeviceAdded = null
        controllerV2Bridge.onDeviceRemoved = null
        if (isReady && nav.pendingRecentlyPlayedReorder) {
            nav.pendingRecentlyPlayedReorder = false
            gameListViewModel.get().moveSelectedToTop()
        }
    }

    override fun onDestroy() {
        controllerV2Bridge.onDeviceAdded = null
        controllerV2Bridge.onDeviceRemoved = null
        controllerV2Bridge.stop(this)
        super.onDestroy()
        unregisterCoreQueryReceiver()
        settings.shutdown()
        if (isReady) {
            systemListViewModel.get().close()
            gameListViewModel.get().close()
        }
        dev.cannoli.scorza.server.KitchenManager.stop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isReady) {
            if (event.action == KeyEvent.ACTION_DOWN
                && bootSequencer.state.value is BootState.Error
                && AndroidGamepadKeyNames.isGamepadEvent(event)) {
                bootSequencer.retry()
            }
            // While in NeedsSetup or NeedsPermission the launcher screen stack drives the wizard
            // via the normal input pipeline, so fall through; everything else is swallowed.
            val bootVal = bootSequencer.state.value
            if (bootVal !is BootState.NeedsSetup && bootVal !is BootState.NeedsPermission) return true
        }
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
                    } else if (isTv && event.action == KeyEvent.ACTION_DOWN) {
                        inputDispatcher.onBack()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bootValOnKeyDown = bootSequencer.state.value
        if (!isReady && bootValOnKeyDown !is BootState.NeedsSetup && bootValOnKeyDown !is BootState.NeedsPermission) {
            if (bootValOnKeyDown is BootState.Error && AndroidGamepadKeyNames.isGamepadEvent(event)) {
                bootSequencer.retry()
            }
            return true
        }
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = true)
            return true
        }
        if (bindingController.keyDown(keyCode)) {
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
        val bootValOnKeyUp = bootSequencer.state.value
        if (!isReady && bootValOnKeyUp !is BootState.NeedsSetup && bootValOnKeyUp !is BootState.NeedsPermission) return true
        val currentScreenForKey = nav.currentScreen
        if (currentScreenForKey is LauncherScreen.InputTester) {
            inputTesterController.dispatchKey(event, down = false)
            return true
        }
        if (inputDispatcher.handleKeyEvent(event)) {
            return true
        }
        if (bindingController.keyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val bootValOnMotion = bootSequencer.state.value
        if (!isReady && bootValOnMotion !is BootState.NeedsSetup && bootValOnMotion !is BootState.NeedsPermission) return super.onGenericMotionEvent(event)
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
            bindingController.keyDown(keyCode)
        } else if (value < 0.3f && wasHeld) {
            held.remove(deviceId)
            bindingController.keyUp(keyCode)
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

    private fun rescanSystemList() {
        launcherActions.get().rescanSystemList()
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
                    settingsViewModel.get().raPassword = ""
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
