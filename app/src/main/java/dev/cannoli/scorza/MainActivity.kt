package dev.cannoli.scorza

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import dev.cannoli.scorza.launcher.GuideOverlayService
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.LocalViewportInsets
import dev.cannoli.scorza.ui.ViewportInsetsPx
import dev.cannoli.scorza.ui.screens.BootErrorScreen
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
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
    @Inject lateinit var onboardingCoordinator: dev.cannoli.scorza.onboarding.OnboardingCoordinator
    @Inject lateinit var inputDispatcher: InputDispatcher
    @Inject lateinit var screenInputRegistry: dev.cannoli.scorza.input.runtime.ScreenInputRegistry
    @Inject lateinit var downloadOutcomeReporter: dev.cannoli.scorza.download.DownloadOutcomeReporter
    @Inject lateinit var menuNavigationPoller: dev.cannoli.scorza.input.runtime.MenuNavigationPoller
    @Inject lateinit var stickAutoRepeat: dev.cannoli.scorza.input.runtime.StickAutoRepeat
    @Inject lateinit var controllerBridge: ControllerBridge
    @Inject lateinit var portRouter: dev.cannoli.scorza.input.runtime.PortRouter
    @Inject lateinit var activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder
    @Inject lateinit var bindingController: BindingController
    @Inject lateinit var osdController: dev.cannoli.ui.components.OsdController
    @Inject lateinit var inputTesterController: InputTesterController
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var coreDownloadService: dev.cannoli.scorza.launcher.CoreDownloadService
    @Inject lateinit var launchManager: Provider<LaunchManager>
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
    @Inject lateinit var autoconfigRepository: dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
    @Inject lateinit var bootSequencer: BootSequencer
    @Inject lateinit var startStorageDependentHolder: StartStorageDependentHolder
    @Inject lateinit var appFonts: AppFonts
    @Inject lateinit var controllerBlacklist: dev.cannoli.scorza.input.ControllerBlacklist
    @Inject lateinit var rommStore: dev.cannoli.scorza.romm.RommConnectionStore
    @Inject lateinit var rommClient: dev.cannoli.scorza.romm.RommClient
    @Inject lateinit var rommDevicePairing: dev.cannoli.scorza.romm.RommDevicePairing
    @Inject lateinit var rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
    // Provider because the loader bakes its disk cache dir in at build time, so it must not be
    // built until the setup flow has settled where Cannoli lives.
    @Inject lateinit var rommImageLoader: Provider<coil.ImageLoader>
    @Inject lateinit var rommDownloader: dev.cannoli.scorza.download.Downloader
    @Inject lateinit var rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher
    @Inject lateinit var syncScheduler: dev.cannoli.scorza.romm.sync.SyncScheduler
    @Inject lateinit var saveSyncStatusHolder: dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
    @Inject lateinit var cannoliPathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider
    @field:dev.cannoli.scorza.di.IoScope @Inject lateinit var ioScope: kotlinx.coroutines.CoroutineScope
    @Inject lateinit var raLoginController: dev.cannoli.scorza.achievements.RaLoginController

    private val isTv: Boolean by lazy { dev.cannoli.scorza.util.DeviceType.isTv(this) }

    // Not Hilt-injected: it holds only in-memory wizard progress for the Activity's lifetime,
    // the same shape as EditButtonsController but without that class's repository dependencies.
    private val legendWizardController = dev.cannoli.scorza.input.legend.LegendWizardController()

    // First run's press run, held for the Activity's lifetime for the same reason.
    private val confirmPressCounter = dev.cannoli.scorza.input.legend.ConfirmPressCounter()

    // Set while a completed run is being held on screen, so presses landing in that window are
    // swallowed rather than starting a second run or advancing twice.
    private var welcomeAdvancePending = false
    private val welcomeHatSync = dev.cannoli.scorza.input.HatKeySync()

    private val isReady: Boolean get() = bootSequencer.state.value is BootState.Ready

    private var pairingUiJob: kotlinx.coroutines.Job? = null
    private var coldStart = true
    // The press that left the welcome step or finished the wizard, held until its key up.
    private var heldAdvanceKey: dev.cannoli.scorza.onboarding.WelcomePress? = null
    private var pendingWizardMapping: dev.cannoli.scorza.input.DeviceMapping? = null

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

    // Optional, so it never touches boot state: only the onboarding step it was granted from
    // needs to notice.
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onboardingCoordinator.refresh()
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
            // Hold the OS splash only until Compose can draw its first frame. From Resolving on, our
            // own themed logo screen renders under it and covers the mount and DI wait, so we hand
            // straight to that instead of freezing on the masked OS icon for the whole resolve.
            when (bootSequencer.state.value) {
                is BootState.Initializing -> !settings.scanLibraryAutomatically
                else -> false
            }
        }
        super.onCreate(savedInstanceState)
        // After super.onCreate, which is where Hilt fills the injected fields in: starting this any
        // earlier reads a lateinit that has not been set and takes the activity down on launch.
        downloadOutcomeReporter.start(lifecycleScope)

        // A launcher intent does not reuse this task when a game is running, it stacks a second
        // MainActivity on top of the emulator, and every icon tap adds another. Standing aside
        // when we are not the task root lets the activity underneath resume, which puts the
        // player back in their game exactly as the task switcher does, and keeps the stack from
        // growing. Only launcher intents qualify: any other caller has a real reason to be here.
        val fromLauncherIcon = intent?.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        if (!isTaskRoot && fromLauncherIcon) {
            finish()
            return
        }

        // Belt-and-suspenders: ensure the launcher window does not hold FLAG_KEEP_SCREEN_ON,
        // so the system display timeout applies. The IGM activity manages its own flag.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()
        editButtonsController.cancelListening()
        loadLoggingPrefs()

        startStorageDependentHolder.register { startStorageDependent() }
        onboardingCoordinator.onFinished = { target -> bootSequencer.onFolderChosen(target) }
        onboardingCoordinator.onRequestPermission = { perm ->
            when (perm) {
                dev.cannoli.scorza.onboarding.OnboardingPermission.STORAGE -> requestStoragePermission()
                dev.cannoli.scorza.onboarding.OnboardingPermission.OVERLAY -> requestOverlayPermission()
            }
        }

        controllerBlacklist.load(this)
        controllerBridge.start(this)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        bootSequencer.advance()

        setContent {
            val boot by bootSequencer.state.collectAsState()
            val appSettings = if (boot is BootState.Ready) settingsViewModel.get().appSettings.collectAsState().value else null
            val themeFont = appSettings?.fontFamily ?: appFonts.mplus1Code
            dev.cannoli.scorza.i18n.ProvideLocalizedResources(appSettings?.languageTag) {
            CannoliTheme(fontFamily = themeFont, iconFontFamily = appFonts.mplus1Code) {
                Surface(modifier = Modifier.fillMaxSize()) {
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
                        is BootState.Resolving -> Box(modifier = Modifier.fillMaxSize().padding(dev.cannoli.scorza.ui.effectiveViewportPadding())) {
                            dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                                kind = dev.cannoli.scorza.ui.screens.HousekeepingKind.STARTING,
                                progress = null,
                                statusLabel = stringResource(R.string.boot_preparing),
                            )
                        }
                        is BootState.NeedsPermission, is BootState.NeedsSetup -> {
                            val storageGranted = (s as? BootState.NeedsPermission)?.storageGranted ?: true
                            LaunchedEffect(storageGranted) { onboardingCoordinator.start() }
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
        val coreUpdate = coreDownloadService.progress.collectAsState().value
        val dlError = updateManager.downloadError.collectAsState().value
        val navScreen = nav.currentScreen
        val navDialogState = nav.dialogState
        val navResumableGames = nav.resumableGames
        val activeMapping by activeMappingHolder.active.collectAsState()
        val syncStatus by saveSyncStatusHolder.state.collectAsState()
        val legendWizardState by legendWizardController.state.collectAsState()
        val confirmPresses by confirmPressCounter.count.collectAsState()
        val portSnapshots by portRouter.entrySnapshots.collectAsState()
        // Pads are enrolled at connect, so first run can render the confirm button it expects
        // before any press. Ports are only assigned on activation, hence the enrollment-order
        // fallback.
        val onboardingMapping = portSnapshots.minByOrNull { it.port ?: Int.MAX_VALUE }?.mapping
        LaunchedEffect(updateInfo) { svm.updateInfo = updateInfo }
        LaunchedEffect(navScreen) {
        }
        LaunchedEffect(legendWizardState.step) {
            if (legendWizardState.step != dev.cannoli.scorza.input.legend.WizardStep.Done) return@LaunchedEffect
            val screen = nav.currentScreen as? LauncherScreen.LegendWizard ?: return@LaunchedEffect
            val base = portRouter.mappingFor(screen.deviceId) ?: return@LaunchedEffect
            val saved = legendWizardController.buildMapping(base)
            // Until the storage step resolves, sdCardRoot is still the internal-storage default, so
            // saving now would orphan the cfg the moment the user picks a card. The mapping applies
            // in memory either way, so the pad works for the rest of first run.
            if (storageRootChosen()) {
                autoconfigRepository.save(saved)
            } else {
                pendingWizardMapping = saved
            }
            portRouter.updateMapping(saved, rebuildEvaluator = true)
            if (activeMappingHolder.active.value?.id == saved.id) activeMappingHolder.set(saved)
            nav.pop()
            // The wizard is first run's recovery path, so finishing it continues the flow rather
            // than dropping the user on a launcher they have not set up yet.
            if (nav.currentScreen is LauncherScreen.OnboardingWelcome) {
                activeMappingHolder.set(saved)
                legendWizardController.confirmKeyCode()?.let {
                    onboardingCoordinator.onWelcomePress(screen.deviceId, it)
                }
            }
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
            coreUpdate = coreUpdate,
            downloadError = dlError,
            osdController = osdController,
            activeMapping = activeMapping,
            editButtonsController = editButtonsController,
            legendWizardState = legendWizardState,
            onboardingMapping = onboardingMapping,
            onboardingConfirmPresses = confirmPresses,
            // The welcome step owns the timeout, because it owns the pips that show it running out.
            onOnboardingRunExpired = { confirmPressCounter.reset() },
            nav = nav,
            inputRouter = router,
            rommBrowseViewModel = rommBrowseViewModel,
            rommImageLoader = rommImageLoader.get(),
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
        settings.sdCardRootOrNull?.let { dev.cannoli.scorza.util.InputLog.init(it) }
        // The chosen path is real from here, so a mapping the wizard built during first run can
        // finally be written, and it has to be written before the settle below re-resolves.
        pendingWizardMapping?.let {
            autoconfigRepository.save(it)
            pendingWizardMapping = null
        }
        controllerBridge.settleNow()
        refreshRommServerVersion()
    }

    /**
     * True once first run has resolved where Cannoli lives. Before that `sdCardRoot` reports its
     * internal-storage default, so nothing may be written through it.
     */
    private fun storageRootChosen(): Boolean = when (bootSequencer.state.value) {
        is BootState.Initializing, BootState.Ready -> true
        else -> false
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

    // A press can arrive from a pad's phantom sibling endpoint, which carries no identity of its
    // own, so resolve the alias before reading the vendor id.
    /**
     * One press on the welcome step, whether it arrived as a key or as a hat direction. Returns
     * true when it completed a run and the step acted on it.
     */
    private fun onWelcomeControllerPress(androidDeviceId: Int, keyCode: Int): Boolean {
        if (welcomeAdvancePending) return true
        if (!confirmPressCounter.press(androidDeviceId, keyCode)) return false
        // A device with no mapping has nothing to verify against.
        val mapping = portRouter.mappingFor(androidDeviceId)
        if (mapping == null) {
            confirmPressCounter.reset()
            return false
        }
        welcomeAdvancePending = true
        lifecycleScope.launch {
            // Let the last pip be seen filled. Acting on this press immediately would clear the run
            // and change the screen in the same frame that completed it.
            kotlinx.coroutines.delay(dev.cannoli.scorza.input.legend.CONFIRM_RUN_COMPLETE_HOLD_MS)
            if (dev.cannoli.scorza.input.legend.verifyConfirmPress(mapping, keyCode)) {
                activeMappingHolder.set(mapping)
                onboardingCoordinator.onWelcomePress(androidDeviceId, keyCode)
            } else {
                startLegendWizard(androidDeviceId, vendorIdFor(androidDeviceId))
            }
            confirmPressCounter.reset()
            welcomeAdvancePending = false
        }
        return true
    }

    private fun vendorIdFor(androidDeviceId: Int): Int? {
        val primary = portRouter.aliasesSnapshot()[androidDeviceId] ?: androidDeviceId
        return portRouter.snapshotEntries().firstOrNull { it.androidDeviceId == primary }?.device?.vendorId
    }

    private fun startLegendWizard(androidDeviceId: Int, vendorId: Int?) {
        val sonyGlyphHint = if (vendorId == dev.cannoli.scorza.input.legend.BuiltInLegendTable.SONY_VID) {
            dev.cannoli.scorza.input.GlyphStyle.SHAPES
        } else {
            null
        }
        legendWizardController.start(sonyGlyphHint)
        nav.push(
            LauncherScreen.LegendWizard(
                deviceId = androidDeviceId,
                duringFirstRun = nav.currentScreen is LauncherScreen.OnboardingScreen,
            )
        )
    }

    private fun registerControllerOsd() {
        controllerBridge.onDeviceAdded = { device ->
            val mapping = portRouter.mappingFor(device.androidDeviceId)
            // The curated entry decides. ConnectedDevice.isBuiltIn is a runtime guess from
            // isExternal plus a Build.MODEL name prefix, and stands in only for a pad the input
            // DB has no opinion about.
            val builtin = mapping?.match?.builtin ?: device.isBuiltIn
            if (!builtin) {
                if (mapping != null && dev.cannoli.scorza.input.legend.shouldRunLegendWizard(mapping)) {
                    startLegendWizard(device.androidDeviceId, device.vendorId)
                } else {
                    val port = portRouter.portFor(device.androidDeviceId)
                    if (port != null) {
                        val name = portRouter.mappingForPort(port)?.displayName?.takeIf { it.isNotEmpty() }
                            ?: device.name.ifEmpty { getString(R.string.device_controller) }
                        osdController.show(getString(R.string.osd_controller_connected, port + 1, name))
                    }
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
        // Re-wire the dispatcher to launcher dispatch shape on each resume, so returning from an
        // emulator always lands on the launcher's wiring.
        router.wire(inputDispatcher)
        registerControllerOsd()
        menuNavigationPoller.start()
        bootSequencer.advance()
        // Optional grants leave boot state untouched, so the wizard step has to reread for itself.
        onboardingCoordinator.refresh()
        launchState.launching = false
        if (nav.dialogState.value is DialogState.Launching) {
            nav.dialogState.value = DialogState.None
        }
        val justExited = launchState.lastLaunched
        if (justExited != null) {
            launchState.lastLaunched = null
            GuideOverlayService.hide(this)
        }
        if (!isReady) return
        // Below the ready guard: during first run there is no library, no RomM connection and no
        // storage root, so nothing the scheduler does is wanted yet. Returning from a game always
        // resumes into Ready, so the exit sync below still fires.
        syncScheduler.start()
        // The just-played save is uploaded by the sweep itself; force one so it runs now.
        if (justExited != null) syncScheduler.syncNow()
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        settings.reload()
        settingsViewModel.get().load()
        val activeDialogState = nav.dialogState
        if (nav.currentScreen is LauncherScreen.RetroAchievements && settings.raToken.isEmpty()) {
            nav.pop()
        }
        if (activeDialogState.value is DialogState.RommConnected && rommStore.token.isNullOrEmpty()) {
            activeDialogState.value = DialogState.None
        }
        router.permissionsHandler.refresh()
        launcherActions.get().refreshLauncherLists()
    }

    override fun onPause() {
        super.onPause()
        // The key up may be delivered elsewhere while backgrounded, which would latch that button
        // out of the launcher for good.
        heldAdvanceKey = null
        syncScheduler.stop()
        menuNavigationPoller.stop()
        // Cancel any in-flight stick auto-repeat so it does not keep firing dispatcher callbacks
        // once the launcher is no longer in front.
        stickAutoRepeat.stop()
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        if (isReady && nav.pendingRecentlyPlayedReorder) {
            nav.pendingRecentlyPlayedReorder = false
            gameListViewModel.get().moveSelectedToTop()
        }
    }

    override fun onDestroy() {
        GuideOverlayService.hide(this)
        controllerBridge.onDeviceAdded = null
        controllerBridge.onDeviceRemoved = null
        controllerBridge.stop(this)
        super.onDestroy()
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
        // Hold the key that left a first-run screen until it is released, so its repeats and its
        // key up cannot land on the screen the press just opened.
        heldAdvanceKey?.let { held ->
            if (held.deviceId == event.deviceId && held.keyCode == event.keyCode) {
                if (event.action == KeyEvent.ACTION_UP) heldAdvanceKey = null
                return true
            }
        }
        // No button meaning is known yet during the wizard, so every raw key down is consumed as a
        // capture attempt rather than falling through to menu/back semantics. Auto-repeats are
        // dropped: a held button would otherwise answer the confirming second press by itself.
        if (cs is LauncherScreen.LegendWizard && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                legendWizardController.onKeyCaptured(event.keyCode)
                // The last capture leaves this screen, so hold that press until it comes up: its
                // key up would otherwise land on whatever the flow moved to.
                if (legendWizardController.state.value.step ==
                    dev.cannoli.scorza.input.legend.WizardStep.Done
                ) {
                    heldAdvanceKey = dev.cannoli.scorza.onboarding.WelcomePress(event.deviceId, event.keyCode)
                }
            }
            return true
        }
        // First run verifies the pad rather than assuming it: a run of presses of one button has to
        // be the confirm button the resolved mapping claims, and anything else means that entry is
        // wrong for this shell. Every press the counter does not see is a press that cannot break a
        // run, so the whole raw stream feeds it and only system keys are left out, unconsumed, so
        // the volume still works.
        if (cs is LauncherScreen.OnboardingWelcome && event.action == KeyEvent.ACTION_DOWN
            && event.repeatCount == 0
            && !dev.cannoli.scorza.onboarding.isSystemKey(event.keyCode)) {
            // Only the press that completes a run leaves the step, so only it needs holding until
            // its key up. The hat path never sets this: its keycode has no key up to clear it.
            if (onWelcomeControllerPress(event.deviceId, event.keyCode)) {
                heldAdvanceKey = dev.cannoli.scorza.onboarding.WelcomePress(event.deviceId, event.keyCode)
            }
            return true
        }
        // The dev keyboard binds BACK to its own back button and needs it to reach the normal
        // pipeline, so the GPIO menu-button shim below is skipped for that device only. Scoped to
        // the event's own device rather than the feature flag: a GPIO menu button is not an
        // enrolled keyboard, so it keeps the shim even if the AVD gate misjudges the host.
        if (!AndroidGamepadKeyNames.isGamepadEvent(event) &&
            !controllerBridge.isDevKeyboardDevice(event.deviceId)) {
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
        // the brief pre-settle window). Scoped to the wizard so other screens keep using v2
        // routing as-is.
        if (currentScreenForKey is LauncherScreen.OnboardingScreen) {
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
        // Pads that report the D-pad as hat axes never send a KEYCODE_DPAD_*, so the welcome step's
        // run of presses would never see the direction that should have broken it.
        if (currentScreenForMotion is LauncherScreen.OnboardingWelcome) {
            welcomeHatSync.sync(
                event.deviceId,
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X),
                event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y),
                { keyCode -> onWelcomeControllerPress(event.deviceId, keyCode) },
                {},
            )
        }
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

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
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

    override fun finishAffinity() = super.finishAffinity()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.cannoli.scorza.i18n.LocaleOverride.wrap(newBase))
    }

    override fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        startActivity(intent, opts)
        Runtime.getRuntime().exit(0)
    }

    override fun startRaLogin(username: String, password: String) {
        raLoginController.login(username, password)
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
