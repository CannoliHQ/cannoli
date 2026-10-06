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
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
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
import dev.cannoli.igm.BindingController
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
    @Inject lateinit var globalOverrides: dev.cannoli.scorza.settings.GlobalOverridesManager
    @Inject lateinit var platformConfig: Provider<PlatformConfig>
    @Inject lateinit var nav: NavigationController
    @Inject lateinit var router: InputRouter
    @Inject lateinit var dialogHandler: dev.cannoli.scorza.input.DialogInputHandler
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
    @Inject lateinit var inputTesterMusic: dev.cannoli.scorza.input.InputTesterMusic
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
    @Inject lateinit var rommImageLoader: Provider<coil.ImageLoader>
    @Inject lateinit var rommDownloader: dev.cannoli.scorza.download.Downloader
    @Inject lateinit var rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher
    @Inject lateinit var syncScheduler: dev.cannoli.scorza.romm.sync.SyncScheduler
    @Inject lateinit var sigilBackfill: Provider<dev.cannoli.scorza.sigil.SigilBackfillService>
    @Inject lateinit var saveMigrationSweep: Provider<dev.cannoli.scorza.saves.SaveMigrationSweep>
    @Inject lateinit var saveSyncStatusHolder: dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
    @Inject lateinit var cannoliPathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider
    @field:dev.cannoli.scorza.di.IoScope @Inject lateinit var ioScope: kotlinx.coroutines.CoroutineScope
    @Inject lateinit var raLoginController: dev.cannoli.scorza.achievements.RaLoginController
    @Inject lateinit var raPendingDrainer: Provider<dev.cannoli.scorza.achievements.RaPendingDrainer>

    private val isTv: Boolean by lazy { dev.cannoli.scorza.util.DeviceType.isTv(this) }

    private val legendWizardController = dev.cannoli.scorza.input.legend.LegendWizardController(
        object : dev.cannoli.scorza.input.legend.WizardShortcutStore {
            override fun read() =
                if (settings.sdCardRootOrNull == null) null else globalOverrides.readShortcuts()
            override fun save(shortcuts: Map<dev.cannoli.igm.ShortcutAction, Set<Int>>) =
                globalOverrides.saveShortcuts(shortcuts)
        },
        chordStagingDirProvider = { java.io.File(filesDir, "shortcuts") },
    )

    private val confirmPressCounter = dev.cannoli.scorza.input.legend.ConfirmPressCounter()

    private var welcomeAdvancePending = false
    private val welcomeHatSync = dev.cannoli.scorza.input.HatKeySync()
    private val magicXKeyInjection = dev.cannoli.scorza.input.runtime.MagicXKeyInjection()

    private val isReady: Boolean get() = bootSequencer.state.value is BootState.Ready

    private var pairingUiJob: kotlinx.coroutines.Job? = null
    private var coldStart = true
    private var heldAdvanceKey: dev.cannoli.scorza.onboarding.WelcomePress? = null

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
            when (bootSequencer.state.value) {
                is BootState.Initializing -> !settings.scanLibraryAutomatically
                else -> false
            }
        }
        super.onCreate(savedInstanceState)
        downloadOutcomeReporter.start(lifecycleScope)

        val fromLauncherIcon = intent?.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        if (!isTaskRoot && fromLauncherIcon) {
            finish()
            return
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
        )

        hideSystemUI()
        editButtonsController.cancelListening()
        loadLoggingPrefs()

        startStorageDependentHolder.register { startStorageDependent() }
        globalOverrides.addShortcutsSavedListener(shortcutsSavedListener)
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

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                bootSequencer.state.first { it is BootState.Ready }
                saveMigrationSweep.get().runOnce()
                val backfill = sigilBackfill.get()
                backfill.setActive(true)
                ioScope.launch { runCatching { raPendingDrainer.get().drain() } }
                val reconnectDrain = dev.cannoli.scorza.achievements.RaReconnectDrain(
                    context = this@MainActivity,
                    scope = ioScope,
                ) { runCatching { raPendingDrainer.get().drain() } }
                reconnectDrain.start()
                try {
                    awaitCancellation()
                } finally {
                    reconnectDrain.stop()
                    backfill.setActive(false)
                }
            }
        }

        setContent {
            val boot by bootSequencer.state.collectAsState()
            val appSettings = if (boot is BootState.Ready) settingsViewModel.get().appSettings.collectAsState().value else null
            val themeFont = appSettings?.fontFamily ?: appFonts.mplus1Code
            dev.cannoli.scorza.i18n.ProvideLocalizedResources(appSettings?.languageTag) {
            CannoliTheme(fontFamily = themeFont, iconFontFamily = appFonts.mplus1Code) {
                Surface(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
                    val menuGlyphPad by activeMappingHolder.active.collectAsState()
                    val menuGlyphShortcuts by menuShortcutsState.collectAsState()
                    CompositionLocalProvider(
                        dev.cannoli.ui.theme.LocalMenuGlyph provides
                            dev.cannoli.scorza.input.launcherMenuGlyph(menuGlyphPad, menuGlyphShortcuts),
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
                                statusLabel = "",
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
        val onboardingMapping = portSnapshots.minByOrNull { it.port ?: Int.MAX_VALUE }?.mapping
        LaunchedEffect(updateInfo) { svm.updateInfo = updateInfo }
        LaunchedEffect(navScreen) {
        }
        LaunchedEffect(legendWizardState.step) {
            if (legendWizardState.step != dev.cannoli.scorza.input.legend.WizardStep.Done) return@LaunchedEffect
            val screen = nav.currentScreen as? LauncherScreen.LegendWizard ?: return@LaunchedEffect
            val base = portRouter.mappingFor(screen.deviceId) ?: return@LaunchedEffect
            val saved = legendWizardController.buildMapping(base)
            autoconfigRepository.save(saved)
            portRouter.updateMapping(saved, rebuildEvaluator = true)
            if (activeMappingHolder.active.value?.id == saved.id) activeMappingHolder.set(saved)
            nav.pop()
            if (nav.currentScreen is LauncherScreen.OnboardingWelcome) {
                activeMappingHolder.set(saved)
                legendWizardController.confirmKeyCode()?.let {
                    onboardingCoordinator.onWelcomePress(screen.deviceId, it)
                }
            }
            activeMappingHolder.set(saved)
            nav.dialogState.value = DialogState.InputTesterOffer(saved.id, screen.deviceId)
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
                val followUp = (nav.currentScreen as? LauncherScreen.InputTester)?.followUpMappingId
                inputTesterController.exit()
                if (nav.screenStack.size > 1) nav.screenStack.removeAt(nav.screenStack.lastIndex)
                if (followUp != null) nav.dialogState.value = DialogState.InputTesterResult(followUp)
            },
            onEnterInputTester = { inputTesterController.enter() },
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
            legendWizardController = legendWizardController,
            legendWizardState = legendWizardState,
            onboardingMapping = onboardingMapping,
            onboardingConfirmPresses = confirmPresses,
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

    private val shortcutsSavedListener: () -> Unit = { refreshMenuShortcuts() }

    private fun refreshMenuShortcuts() {
        val shortcuts = if (settings.sdCardRootOrNull == null) emptyMap()
        else runCatching { globalOverrides.readShortcuts() }.getOrDefault(emptyMap())
        inputDispatcher.setMenuShortcuts(shortcuts)
        menuShortcutsState.value = shortcuts
    }

    private val menuShortcutsState =
        kotlinx.coroutines.flow.MutableStateFlow<Map<dev.cannoli.igm.ShortcutAction, Set<Int>>>(emptyMap())

    private fun startStorageDependent() {
        settings.reload()
        settings.sdCardRootOrNull?.let { dev.cannoli.scorza.util.InputLog.init(it) }
        autoconfigRepository.promoteStaging()
        legendWizardController.flushMenuChord()
        refreshMenuShortcuts()
        controllerBridge.settleNow()
        refreshRommServerVersion()
    }

    private fun refreshRommServerVersion() {
        if (!rommStore.isConfigured) return
        lifecycleScope.launch {
            val version = withContext(Dispatchers.IO) { rommClient.serverVersion() }
            if (version != null) rommStore.serverVersion = version
            val media = withContext(Dispatchers.IO) { rommClient.scanMedia() }
            if (media.isNotEmpty()) rommStore.scanMedia = media.toSet()
        }
    }

    private fun onWelcomeControllerPress(androidDeviceId: Int, keyCode: Int): Boolean {
        if (welcomeAdvancePending) return true
        if (!confirmPressCounter.press(androidDeviceId, keyCode)) return false
        val mapping = portRouter.mappingFor(androidDeviceId)
        if (mapping == null) {
            confirmPressCounter.reset()
            return false
        }
        welcomeAdvancePending = true
        lifecycleScope.launch {
            kotlinx.coroutines.delay(dev.cannoli.scorza.input.legend.CONFIRM_RUN_COMPLETE_HOLD_MS)
            if (dev.cannoli.scorza.input.legend.verifyConfirmPress(mapping, keyCode)) {
                activeMappingHolder.set(mapping)
                onboardingCoordinator.onWelcomePress(androidDeviceId, keyCode)
            } else {
                startLegendWizard(androidDeviceId, confirmedKeyCode = keyCode)
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

    private fun startLegendWizard(androidDeviceId: Int, confirmedKeyCode: Int? = null) {
        legendWizardController.start(confirmedKeyCode)
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
            val builtin = mapping?.match?.builtin ?: device.isBuiltIn
            val welcomeOwnsIt = nav.currentScreen is LauncherScreen.OnboardingWelcome
            when {
                !welcomeOwnsIt && mapping != null &&
                    dev.cannoli.scorza.input.legend.shouldRunLegendWizard(mapping) ->
                    startLegendWizard(device.androidDeviceId)
                !builtin -> {
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
        magicXKeyInjection.suppress()
        router.wire(inputDispatcher)
        refreshMenuShortcuts()
        registerControllerOsd()
        dialogHandler.onSyncSavesNow = { syncScheduler.syncNow() }
        dialogHandler.onRestartControllerWizard = { deviceId ->
            legendWizardController.resumeAtLastPrompt()
            nav.push(
                LauncherScreen.LegendWizard(
                    deviceId = deviceId,
                    duringFirstRun = nav.currentScreen is LauncherScreen.OnboardingScreen,
                )
            )
        }
        menuNavigationPoller.start()
        bootSequencer.advance()
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
        syncScheduler.start()
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
        heldAdvanceKey = null
        syncScheduler.stop()
        menuNavigationPoller.stop()
        inputTesterMusic.stop()
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
        globalOverrides.removeShortcutsSavedListener(shortcutsSavedListener)
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
            val bootVal = bootSequencer.state.value
            if (bootVal !is BootState.NeedsSetup && bootVal !is BootState.NeedsPermission) return true
        }
        val cs = nav.currentScreen
        if (cs is LauncherScreen.EditButtons && editButtonsController.isListening
            && event.action == KeyEvent.ACTION_DOWN) {
            editButtonsController.captureRawKeyEvent(event.keyCode)
            return true
        }
        heldAdvanceKey?.let { held ->
            if (held.deviceId == event.deviceId && held.keyCode == event.keyCode) {
                if (event.action == KeyEvent.ACTION_UP) heldAdvanceKey = null
                return true
            }
        }
        if (cs is LauncherScreen.LegendWizard && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                legendWizardController.onKeyCaptured(event.keyCode)
                if (legendWizardController.state.value.step ==
                    dev.cannoli.scorza.input.legend.WizardStep.Done
                ) {
                    heldAdvanceKey = dev.cannoli.scorza.onboarding.WelcomePress(event.deviceId, event.keyCode)
                }
            }
            return true
        }
        if (cs is LauncherScreen.OnboardingWelcome && event.action == KeyEvent.ACTION_DOWN
            && event.repeatCount == 0
            && !dev.cannoli.scorza.onboarding.isSystemKey(event.keyCode)) {
            if (onWelcomeControllerPress(event.deviceId, event.keyCode)) {
                heldAdvanceKey = dev.cannoli.scorza.onboarding.WelcomePress(event.deviceId, event.keyCode)
            }
            return true
        }
        if (!AndroidGamepadKeyNames.isGamepadEvent(event) &&
            !controllerBridge.isDevKeyboardDevice(event.deviceId)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val currentScreenForKey = nav.currentScreen
                    if (currentScreenForKey is LauncherScreen.InputTester) {
                        inputTesterController.dispatchKey(event, down = event.action == KeyEvent.ACTION_DOWN)
                    } else if (event.action == KeyEvent.ACTION_DOWN) {
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
        if (csForListen is LauncherScreen.LegendWizard) {
            val axes = listOf(0, 1, 11, 14, 15, 16, 17, 18, 22, 23)
            legendWizardController.captureRawAxisEvent(axes.associateWith { event.getAxisValue(it) })
            return true
        }
        val source = event.source
        val isJoystick =
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        val currentScreenForMotion = nav.currentScreen
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
