package dev.cannoli.scorza

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import dev.cannoli.scorza.input.runtime.InputDispatcher
import dev.cannoli.scorza.input.InputRouter
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.runtime.ControllerBridge
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.ActivityDisplayRouter
import dev.cannoli.scorza.launcher.BlackGameScreenActivity
import dev.cannoli.scorza.launcher.ExternalGameSessionActivity
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.LauncherDisplayTransition
import dev.cannoli.scorza.launcher.launcherDimOverlayAlpha
import dev.cannoli.scorza.launcher.isSystemMediaKey
import dev.cannoli.scorza.launcher.intendedLauncherDisplayId
import dev.cannoli.scorza.launcher.launcherDisplayTransition
import dev.cannoli.scorza.launcher.noAnimationActivityOptions
import dev.cannoli.scorza.launcher.shouldBlankGameScreen
import dev.cannoli.scorza.launcher.shouldDimLauncherScreen
import dev.cannoli.scorza.launcher.setLauncherWindowInputBlocked
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.LocalViewportInsets
import dev.cannoli.scorza.ui.ViewportInsetsPx
import dev.cannoli.scorza.ui.screens.BootErrorScreen
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.updater.UpdateManager
import dev.cannoli.ui.theme.CannoliTheme
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
    @Inject lateinit var screenInputRegistry: dev.cannoli.scorza.input.runtime.ScreenInputRegistry
    @Inject lateinit var menuNavigationPoller: dev.cannoli.scorza.input.runtime.MenuNavigationPoller
    @Inject lateinit var stickAutoRepeat: dev.cannoli.scorza.input.runtime.StickAutoRepeat
    @Inject lateinit var controllerBridge: ControllerBridge
    @Inject lateinit var portRouter: dev.cannoli.scorza.input.runtime.PortRouter
    @Inject lateinit var activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder
    @Inject lateinit var bindingController: BindingController
    @Inject lateinit var osdController: dev.cannoli.ui.components.OsdController
    @Inject lateinit var inputTesterController: InputTesterController
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var setupCoordinator: SetupCoordinator
    @Inject lateinit var launchManager: Provider<LaunchManager>
    @Inject lateinit var activityDisplayRouter: ActivityDisplayRouter
    @Inject lateinit var launchState: dev.cannoli.scorza.launcher.LaunchState
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
    @Inject lateinit var mappingRepository: Provider<dev.cannoli.scorza.input.repo.MappingRepository>
    @Inject lateinit var bootSequencer: BootSequencer
    @Inject lateinit var startStorageDependentHolder: StartStorageDependentHolder
    @Inject lateinit var appFonts: AppFonts
    @Inject lateinit var controllerBlacklist: dev.cannoli.scorza.input.ControllerBlacklist
    @Inject lateinit var rommStore: dev.cannoli.scorza.romm.RommConnectionStore
    @Inject lateinit var rommClient: dev.cannoli.scorza.romm.RommClient
    @Inject lateinit var rommDevicePairing: dev.cannoli.scorza.romm.RommDevicePairing
    @Inject lateinit var rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
    @Inject lateinit var rommImageLoader: coil.ImageLoader
    @Inject lateinit var rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader
    @Inject lateinit var rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher
    @Inject lateinit var syncScheduler: dev.cannoli.scorza.romm.sync.SyncScheduler
    @Inject lateinit var saveSyncStatusHolder: dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
    @Inject lateinit var cannoliPathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider
    @field:dev.cannoli.scorza.di.IoScope @Inject lateinit var ioScope: kotlinx.coroutines.CoroutineScope

    private val isTv: Boolean by lazy { dev.cannoli.scorza.util.DeviceType.isTv(this) }

    private val isReady: Boolean get() = bootSequencer.state.value is BootState.Ready

    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private var pairingUiJob: kotlinx.coroutines.Job? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var coldStart = true
    private var launcherInputBlocked = false
    private var launcherDimmed by mutableStateOf(false)

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

    private fun loadLoggingPrefs() {
        dev.cannoli.scorza.util.LoggingPrefs.romScan = settings.loggingRomScan
        dev.cannoli.scorza.util.LoggingPrefs.input = settings.loggingInput
        dev.cannoli.scorza.util.LoggingPrefs.session = settings.loggingSession
        dev.cannoli.scorza.util.LoggingPrefs.kitchen = settings.loggingKitchen
        dev.cannoli.scorza.util.LoggingPrefs.storage = settings.loggingStorage
        dev.cannoli.scorza.util.LoggingPrefs.romm = settings.loggingRomm
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            if (!::bootSequencer.isInitialized) return@setKeepOnScreenCondition true
            val state = bootSequencer.state.value
            when (state) {
                BootState.Resolving -> true
                is BootState.Initializing -> !settings.scanLibraryAutomatically
                else -> false
            }
        }
        super.onCreate(savedInstanceState)
        // When Cannoli is the system Home activity, keep a task covering the primary display
        // before moving the launcher away. Otherwise Android immediately relaunches Home on the
        // uncovered primary display and Main/Black activities ping-pong until boot completes.
        syncBlackGameScreen()
        moveLauncherToPreferredDisplay()

        // Belt-and-suspenders: ensure the launcher window does not hold FLAG_KEEP_SCREEN_ON,
        // so the system display timeout applies. The IGM activity manages its own flag.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            launchState.gameActive.collect(::syncLauncherDimming)
        }

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()
        editButtonsController.cancelListening()
        loadLoggingPrefs()

        startStorageDependentHolder.register { startStorageDependent() }
        onboardingHandler.onFolderChosen = { target -> bootSequencer.onFolderChosen(target) }
        onboardingHandler.onRequestPermission = { perm ->
            when (perm) {
                dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE -> requestStoragePermission()
            }
        }
        router.unregisterCoreQueryReceiver = { unregisterCoreQueryReceiver() }

        controllerBlacklist.load(this)
        controllerBridge.start(this)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        bootSequencer.advance()

        setContent {
            val boot by bootSequencer.state.collectAsState()
            LaunchedEffect(boot) {
                if (boot is BootState.Ready) {
                    syncBlackGameScreen()
                    syncLauncherDimming()
                }
            }

            val themeFont = if (boot is BootState.Ready) {
                settingsViewModel.get().appSettings.collectAsState().value.fontFamily
            } else {
                appFonts.mplus1Code
            }
            CannoliTheme(fontFamily = themeFont, iconFontFamily = appFonts.mplus1Code) {
                val dimOverlayAlpha = launcherDimOverlayAlpha(launcherDimmed)
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            if (dimOverlayAlpha > 0f) {
                                drawRect(Color.Black, alpha = dimOverlayAlpha)
                            }
                        }
                ) {
                    CompositionLocalProvider(
                        LocalViewportInsets provides ViewportInsetsPx(
                            geometryWidthPct = settings.screenGeometryWidth,
                            geometryHeightPct = settings.screenGeometryHeight,
                            geometryXPct = settings.screenGeometryX,
                            geometryYPct = settings.screenGeometryY,
                            portraitMarginPx = settings.portraitMarginPx,
                        ),
                        dev.cannoli.scorza.input.screen.compose.LocalScreenInputRegistry provides screenInputRegistry,
                    ) {
                    when (val s = boot) {
                        is BootState.Resolving -> Box(modifier = Modifier.fillMaxSize()) {}
                        is BootState.NeedsPermission, is BootState.NeedsSetup -> {
                            val storageGranted = (s as? BootState.NeedsPermission)?.storageGranted ?: true
                            LaunchedEffect(storageGranted) {
                                val perms = listOf(dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                val granted = buildSet {
                                    if (storageGranted) add(dev.cannoli.scorza.navigation.OnboardingPermission.STORAGE)
                                }
                                val volumes = setupCoordinator.detectStorageVolumes() + ("Custom" to "")
                                val existingIndex = setupCoordinator.existingCannoliVolumeIndex(volumes)
                                val preselectIndex = existingIndex ?: 0
                                val top = nav.currentScreen
                                if (top is LauncherScreen.OnboardingPermissions) {
                                    val nowAllGranted = granted.containsAll(perms)
                                    val newSelected = if (nowAllGranted && !top.allGranted) perms.size else top.selectedIndex
                                    nav.replaceTop(top.copy(
                                        permissions = perms,
                                        granted = granted,
                                        volumes = volumes,
                                        volumeIndex = preselectIndex,
                                        selectedIndex = newSelected,
                                        existingInstallVolumeIndex = existingIndex,
                                    ))
                                } else if (top !is LauncherScreen.DirectoryBrowser) {
                                    nav.screenStack.clear()
                                    nav.screenStack.add(LauncherScreen.OnboardingPermissions(
                                        permissions = perms,
                                        granted = granted,
                                        volumes = volumes,
                                        volumeIndex = preselectIndex,
                                        existingInstallVolumeIndex = existingIndex,
                                    ))
                                }
                            }
                            ReadyNavGraph()
                        }
                        is BootState.Initializing -> {
                            if (settings.scanLibraryAutomatically) {
                                val kind = when (s.phase) {
                                    dev.cannoli.scorza.boot.BootPhase.IMPORT ->
                                        dev.cannoli.scorza.ui.screens.HousekeepingKind.DATABASE_MIGRATION
                                    dev.cannoli.scorza.boot.BootPhase.INITIAL_SCAN ->
                                        dev.cannoli.scorza.ui.screens.HousekeepingKind.INITIAL_SCAN
                                    dev.cannoli.scorza.boot.BootPhase.LIBRARY_REFRESH ->
                                        dev.cannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH
                                }
                                Box(modifier = Modifier.fillMaxSize().padding(dev.cannoli.scorza.ui.effectiveViewportPadding())) {
                                    dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                                        kind = kind,
                                        progress = s.progress,
                                        statusLabel = s.label,
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {}
                            }
                        }
                        is BootState.Error -> BootErrorScreen(message = s.message)
                        is BootState.Ready -> ReadyNavGraph()
                    }
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
        val syncStatus by saveSyncStatusHolder.state.collectAsState()
        LaunchedEffect(updateInfo) { svm.updateInfo = updateInfo }
        LaunchedEffect(navScreen) {
        }
        val currentDialog by navDialogState.collectAsState()
        val kitchenVisible = currentDialog is DialogState.Kitchen
        LaunchedEffect(kitchenVisible) {
            if (kitchenVisible) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
            onListStateChanged = { listState -> nav.activeListState = listState },
            resumableGames = navResumableGames,
            updateAvailable = updateInfo != null,
            downloadProgress = dlProgress ?: 0f,
            downloadError = dlError,
            osdController = osdController,
            activeMapping = activeMapping,
            mappingRepository = mappingRepository.get(),
            editButtonsController = editButtonsController,
            nav = nav,
            inputRouter = router,
            rommBrowseViewModel = rommBrowseViewModel,
            rommImageLoader = rommImageLoader,
            rommHost = rommStore.host,
            rommArtType = rommStore.artTypeFlow.collectAsState().value,
            rommDownloader = rommDownloader,
            rommArtFetcher = rommArtFetcher,
            saveSyncStatus = syncStatus,
        )
    }

    /**
     * Re-run the storage-backed parts of input setup once MANAGE_EXTERNAL_STORAGE is available:
     * point the input log at the SD card and re-settle so controllers pick up saved profiles.
     * The controller bridge itself is started in onCreate (before permission) so the onboarding
     * wizard is operable. BootSequencer invokes this once, on the edge into Initializing.
     */
    private fun startStorageDependent() {
        settings.reload()
        // This callback runs synchronously before BootSequencer exposes its Initializing state,
        // so the idle game display is already black when the library animation begins.
        syncBlackGameScreen()
        syncLauncherDimming()
        if (settings.sdCardRoot.isNotEmpty()) {
            dev.cannoli.scorza.util.InputLog.init(settings.sdCardRoot)
        }
        controllerBridge.settleNow()
        refreshRommServerVersion()
    }

    private fun refreshRommServerVersion() {
        if (!rommStore.isConfigured) return
        lifecycleScope.launch {
            // The server can be upgraded behind our back; the version cached at pairing time would
            // otherwise gate save sync forever. Keep the cached value when the server is unreachable.
            val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
            if (version != null) rommStore.serverVersion = version
            val media = withContext(Dispatchers.IO) { rommClient.scanMedia() }
            if (media.isNotEmpty()) rommStore.scanMedia = media.toSet()
        }
    }

    private fun registerControllerOsd() {
        controllerBridge.onDeviceAdded = { device ->
            if (!device.isBuiltIn) {
                val port = portRouter.portFor(device.androidDeviceId)
                if (port != null) {
                    val name = portRouter.mappingForPort(port)?.displayName?.takeIf { it.isNotEmpty() }
                        ?: device.name.ifEmpty { getString(R.string.device_controller) }
                    osdController.show(getString(R.string.osd_controller_connected, port + 1, name))
                }
            }
        }
        controllerBridge.onDeviceRemoved = { departed ->
            val msg = departed.port?.let {
                getString(R.string.osd_controller_disconnected_port, it + 1, departed.displayName)
            } ?: getString(R.string.osd_controller_disconnected, departed.displayName)
            osdController.show(msg)
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        syncLauncherDimming()
        // Re-wire the dispatcher to launcher dispatch shape on each resume. LibretroActivity
        // overwrites these callbacks with IGM-specific wiring when it runs; we restore the
        // launcher's wiring when we come back.
        if (!launcherInputBlocked) {
            router.wire(inputDispatcher)
            registerControllerOsd()
            menuNavigationPoller.start()
        }
        bootSequencer.advance()
        launchState.launching = false
        val justExited = launchState.lastLaunched
        if (justExited != null) {
            dev.cannoli.scorza.util.LaunchLog.write(
                "launcher resumed rom=${justExited.path.absolutePath} " +
                    "display=${windowManager.defaultDisplay.displayId} libretroRunning=${LibretroActivity.isRunning}"
            )
        }
        if (justExited != null && !LibretroActivity.isRunning) {
            launchState.lastLaunched = null
            if (activityDisplayRouter.gameLaunchDisplayId() == null) {
                launchState.markGameEnded()
            }
        }
        if (!LibretroActivity.isRunning) {
            syncScheduler.start()
            // The just-played save is uploaded by the sweep itself; force one so it runs now.
            if (justExited != null) syncScheduler.syncNow()
        }
        if (!isReady) return
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = noAnimationActivityOptions(activityDisplayRouter.gameLaunchDisplayId())
            startActivity(intent, opts)
            return
        }
        settings.reload()
        settingsViewModel.get().load()
        syncBlackGameScreen()
        syncLauncherDimming()
        val activeDialogState = nav.dialogState
        if (activeDialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
            activeDialogState.value = DialogState.None
        }
        if (activeDialogState.value is DialogState.RommConnected && rommStore.token.isNullOrEmpty()) {
            activeDialogState.value = DialogState.None
        }
        launcherActions.get().refreshLauncherLists()
    }

    override fun onPause() {
        super.onPause()
        syncScheduler.stop()
        menuNavigationPoller.stop()
        // Cancel any in-flight stick auto-repeat so it does not keep firing dispatcher callbacks
        // after LibretroActivity has rewired them.
        stickAutoRepeat.stop()
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        if (isReady && nav.pendingRecentlyPlayedReorder) {
            nav.pendingRecentlyPlayedReorder = false
            gameListViewModel.get().moveSelectedToTop()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post(::hideSystemUI)
    }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(ExternalGameSessionActivity.EXTRA_GAME_SESSION_RETURN, false)) return

        launchState.launching = false
        launchState.markGameEnded()
        val justExited = launchState.lastLaunched
        launchState.lastLaunched = null
        dev.cannoli.scorza.util.LaunchLog.write(
            "launcher focus restored display=${windowManager.defaultDisplay.displayId} " +
                "rom=${justExited?.path?.absolutePath ?: "<none>"}"
        )
        if (isReady) {
            syncScheduler.start()
            if (justExited != null) syncScheduler.syncNow()
            launcherActions.get().refreshLauncherLists()
        }
        hideSystemUI()
    }

    override fun onDestroy() {
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        controllerBridge.stop(this)
        super.onDestroy()
        unregisterCoreQueryReceiver()
        loginPollHandler.removeCallbacks(loginPollRunnable)
        loginManager?.destroy()
        loginManager = null
        settings.shutdown()
        if (isReady) {
            systemListViewModel.get().close()
            gameListViewModel.get().close()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isSystemMediaKey(event.keyCode)) return super.dispatchKeyEvent(event)
        if (launcherInputBlocked) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            dev.cannoli.scorza.util.InputLog.write(
                "[launcher dispatch] keyCode=${event.keyCode} source=0x${event.source.toString(16)}"
            )
        }
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
                    } else if (event.action == KeyEvent.ACTION_DOWN) {
                        // KEYCODE_BACK is a default BTN_MENU binding, but handhelds that wire the menu
                        // button to GPIO deliver it keyboard-sourced from a device ControllerBridge never
                        // routes, so it has no PortRouter entry and can never resolve through the mapping.
                        // Call onMenu() directly so it behaves like a mapped BTN_MENU. TV keeps back-nav.
                        dev.cannoli.scorza.util.InputLog.write(
                            "back key: isTv=$isTv -> ${if (isTv) "onBack" else "onMenu"}"
                        )
                        if (isTv) inputDispatcher.onBack() else inputDispatcher.onMenu()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isSystemMediaKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (launcherInputBlocked) return true
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
        // Onboarding wizard fallback: route raw D-pad / button keycodes when no device has been
        // routed by the v2 bridge yet (e.g. TV remotes that aren't classified as gamepads, or
        // the brief pre-settle window). Scoped to OnboardingPermissions so other screens keep
        // using v2 routing as-is.
        if (currentScreenForKey is LauncherScreen.OnboardingPermissions) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { inputDispatcher.onUp(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { inputDispatcher.onDown(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { inputDispatcher.onLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { inputDispatcher.onRight(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    { inputDispatcher.onConfirm(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B ->
                    { inputDispatcher.onBack(); return true }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU ->
                    { inputDispatcher.onStart(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isSystemMediaKey(keyCode)) return super.onKeyUp(keyCode, event)
        if (launcherInputBlocked) return true
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
        if (launcherInputBlocked) return true
        val bootValOnMotion = bootSequencer.state.value
        if (!isReady && bootValOnMotion !is BootState.NeedsSetup && bootValOnMotion !is BootState.NeedsPermission) return super.onGenericMotionEvent(event)
        val currentScreenForMotion = nav.currentScreen
        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }
        val handled = inputDispatcher.handleMotionEvent(event)
        stickAutoRepeat.handleMotion(event, dispatcherHandled = handled)
        return handled || super.onGenericMotionEvent(event)
    }

    private val triggerL2HeldDevices = mutableSetOf<Int>()
    private val triggerR2HeldDevices = mutableSetOf<Int>()
    private val bindingHatSync = dev.cannoli.scorza.input.HatKeySync()

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
        if (launcherInputBlocked) return true
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
            bindingHatSync.sync(
                event.deviceId,
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y),
                { bindingController.keyDown(it) },
                { bindingController.keyUp(it) },
            )
        }

        if (currentScreenForMotion is LauncherScreen.InputTester) {
            inputTesterController.dispatchMotion(event)
            return true
        }

        return super.dispatchGenericMotionEvent(event)
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

    override fun applyLauncherDisplayPreference() {
        @Suppress("DEPRECATION")
        val currentDisplayId = windowManager.defaultDisplay.displayId
        val targetDisplayId = activityDisplayRouter.preferredLauncherDisplayId(
            forcePrimaryWhenDisabled = true,
        )
        when (
            launcherDisplayTransition(
                currentDisplayId = currentDisplayId,
                targetDisplayId = targetDisplayId,
                gameDisplayId = activityDisplayRouter.gameLaunchDisplayId(),
            )
        ) {
            LauncherDisplayTransition.SYNC_IN_PLACE -> {
                syncBlackGameScreen()
                syncLauncherDimming()
            }
            LauncherDisplayTransition.COVER_GAME_DISPLAY_THEN_MOVE -> {
                // Enabling: cover the Home display before moving MainActivity away from it.
                syncBlackGameScreen()
                syncLauncherDimming()
                moveLauncherToPreferredDisplay(forcePrimaryWhenDisabled = true)
            }
            LauncherDisplayTransition.MOVE_THEN_SYNC_ON_DESTINATION -> {
                // Disabling: keep the black task in place until MainActivity reaches primary.
                // The destination activity's create/resume path will dismiss it safely.
                syncLauncherDimming()
                moveLauncherToPreferredDisplay(forcePrimaryWhenDisabled = true)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun syncLauncherDimming(gameActive: Boolean = launchState.gameActive.value) {
        val launcherDisplayId = windowManager.defaultDisplay.displayId
        val dim = shouldDimLauncherScreen(
            experimentalFeatures = settings.experimentalFeatures,
            dualScreenLaunching = settings.dualScreenLaunching,
            dimLauncherDuringGames = settings.dimLauncherDuringGames,
            gameActive = gameActive,
            gameDisplayId = activityDisplayRouter.gameLaunchDisplayId(),
            launcherDisplayId = launcherDisplayId,
        )
        launcherDimmed = dim
        updateLauncherInputBlock(dim)
    }

    private fun updateLauncherInputBlock(blocked: Boolean) {
        if (launcherInputBlocked == blocked) return
        launcherInputBlocked = blocked
        setLauncherWindowInputBlocked(window, blocked)
        if (blocked) {
            router.cancelPendingInput()
            stickAutoRepeat.stop()
            menuNavigationPoller.stop()
            portRouter.resetAllEvaluators()
        } else if (
            isReady && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            menuNavigationPoller.start()
        }
    }

    @Suppress("DEPRECATION")
    private fun syncBlackGameScreen() {
        val launcherDisplayId = intendedLauncherDisplayId(
            currentDisplayId = windowManager.defaultDisplay.displayId,
            preferredDisplayId = activityDisplayRouter.preferredLauncherDisplayId(),
        )
        val gameDisplayId = activityDisplayRouter.gameLaunchDisplayId()
        val shouldShow = shouldBlankGameScreen(
            experimentalFeatures = settings.experimentalFeatures,
            dualScreenLaunching = settings.dualScreenLaunching,
            topScreenBlackout = settings.topScreenBlackout,
            cannoliIsDefaultHome = isCannoliDefaultHome(),
            gameDisplayId = gameDisplayId,
            launcherDisplayId = launcherDisplayId,
        )
        if (!shouldShow) {
            dismissBlackGameScreen()
            return
        }

        val targetDisplayId = gameDisplayId ?: return
        if (BlackGameScreenActivity.isShowingOrLaunchingOn(targetDisplayId)) return

        dismissBlackGameScreen()
        BlackGameScreenActivity.markLaunchPending(targetDisplayId)
        try {
            startActivity(
                BlackGameScreenActivity.intent(this),
                noAnimationActivityOptions(targetDisplayId),
            )
        } catch (e: RuntimeException) {
            dismissBlackGameScreen()
            dev.cannoli.scorza.util.ErrorLog.error(
                "black game screen failed: display=$targetDisplayId",
                e,
            )
        }
    }

    private fun dismissBlackGameScreen() {
        BlackGameScreenActivity.finishIfRunning()
    }

    private fun isCannoliDefaultHome(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == packageName
    }

    @Suppress("DEPRECATION")
    private fun moveLauncherToPreferredDisplay(forcePrimaryWhenDisabled: Boolean = false) {
        val targetDisplayId = activityDisplayRouter.preferredLauncherDisplayId(
            forcePrimaryWhenDisabled = forcePrimaryWhenDisabled
        ) ?: return
        if (windowManager.defaultDisplay.displayId == targetDisplayId) return

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        try {
            startActivity(intent, noAnimationActivityOptions(targetDisplayId))
        } catch (e: RuntimeException) {
            dev.cannoli.scorza.util.ErrorLog.error(
                "launcher display move failed: display=$targetDisplayId",
                e,
            )
        }
    }

    override fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val opts = noAnimationActivityOptions(
            activityDisplayRouter.preferredLauncherDisplayId()
        )
        startActivity(intent, opts)
        Runtime.getRuntime().exit(0)
    }

    override fun startRaLogin(username: String, password: String) {
        val ra = RetroAchievementsManager(
            context = this,
            onLogin = { success, nameOrError, token ->
                if (success && token != null) {
                    settings.raUsername = nameOrError
                    settings.raToken = token
                    settings.raPassword = password
                    settingsViewModel.get().raPassword = ""
                    nav.dialogState.value = DialogState.RAAccount(username = nameOrError)
                } else {
                    nav.dialogState.value = DialogState.RALoggingIn(message = getString(R.string.ra_login_invalid))
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

    override fun startRommPairing(host: String) {
        rommDevicePairing.start(host)
        pairingUiJob?.cancel()
        pairingUiJob = lifecycleScope.launch {
            rommDevicePairing.state.collect { state ->
                when (state) {
                    is dev.cannoli.scorza.romm.PairingState.Idle -> {}
                    is dev.cannoli.scorza.romm.PairingState.Connecting ->
                        nav.dialogState.value = DialogState.RommPairing(host = host)
                    is dev.cannoli.scorza.romm.PairingState.WaitingApproval -> {
                        val qr = withContext(Dispatchers.Default) {
                            dev.cannoli.scorza.util.QrCode.generate(state.verificationUrl, 256)
                        }
                        nav.dialogState.value = DialogState.RommPairing(
                            host = rommStore.host,
                            waitingApproval = true,
                            qrBitmap = qr,
                        )
                    }
                    is dev.cannoli.scorza.romm.PairingState.Success -> {
                        val connected = completeRommConnection()
                        if (rommDevicePairing.state.value is dev.cannoli.scorza.romm.PairingState.Success) {
                            nav.dialogState.value = connected
                        }
                    }
                    is dev.cannoli.scorza.romm.PairingState.Failed ->
                        nav.dialogState.value = DialogState.RommPairing(
                            host = rommStore.host,
                            message = pairingFailureMessage(state.reason),
                        )
                }
            }
        }
    }

    override fun startRommCodePairing(host: String, pairCode: String) {
        if (!dev.cannoli.scorza.romm.RommPairingCode.isValid(pairCode)) {
            nav.dialogState.value = DialogState.RommPairing(host = host, message = getString(R.string.romm_pair_invalid))
            return
        }
        nav.dialogState.value = DialogState.RommPairing(host = host)
        lifecycleScope.launch {
            val base = withContext(Dispatchers.IO) { rommClient.resolveBaseUrl(host) }
            if (base == null) {
                nav.dialogState.value = DialogState.RommPairing(host = host, message = getString(R.string.romm_unreachable))
                return@launch
            }
            rommStore.host = base
            val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
            if (dev.cannoli.scorza.romm.RommCapabilities.isKnownUnsupported(version)) {
                nav.dialogState.value = DialogState.RommPairing(host = base, message = getString(R.string.romm_server_too_old))
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { rommClient.exchangeCode(pairCode) } }
            result.onSuccess { token ->
                rommStore.token = token
                val connected = completeRommConnection()
                if (nav.dialogState.value is DialogState.RommPairing) {
                    nav.dialogState.value = connected
                }
            }.onFailure { e ->
                val msg = when ((e as? dev.cannoli.scorza.romm.RommException)?.statusCode) {
                    404 -> getString(R.string.romm_pair_invalid)
                    429 -> getString(R.string.romm_pair_rate_limited)
                    else -> getString(R.string.romm_pair_failed)
                }
                if (nav.dialogState.value is DialogState.RommPairing) {
                    nav.dialogState.value = DialogState.RommPairing(host = host, message = msg)
                }
            }
        }
    }

    private suspend fun completeRommConnection(): DialogState.RommConnected {
        settingsViewModel.get().exitSubList()
        val user = withContext(Dispatchers.IO) { rommClient.currentUser() }
        val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
        val media = withContext(Dispatchers.IO) { rommClient.scanMedia() }
        rommStore.username = user
        rommStore.serverVersion = version
        if (media.isNotEmpty()) rommStore.scanMedia = media.toSet()
        return DialogState.RommConnected(host = rommStore.host, username = user, version = version)
    }

    private fun pairingFailureMessage(reason: dev.cannoli.scorza.romm.PairingFailure): String = when (reason) {
        dev.cannoli.scorza.romm.PairingFailure.SERVER_TOO_OLD -> getString(R.string.romm_server_too_old)
        dev.cannoli.scorza.romm.PairingFailure.UNREACHABLE -> getString(R.string.romm_unreachable)
        dev.cannoli.scorza.romm.PairingFailure.DENIED -> getString(R.string.romm_pair_denied)
        dev.cannoli.scorza.romm.PairingFailure.EXPIRED -> getString(R.string.romm_pair_expired)
        dev.cannoli.scorza.romm.PairingFailure.RATE_LIMITED -> getString(R.string.romm_pair_rate_limited)
        dev.cannoli.scorza.romm.PairingFailure.FAILED -> getString(R.string.romm_pair_failed)
    }

}
