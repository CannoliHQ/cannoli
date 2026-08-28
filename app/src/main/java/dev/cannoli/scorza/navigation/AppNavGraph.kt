package dev.cannoli.scorza.navigation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.scorza.ui.LocalViewportInsets
import dev.cannoli.scorza.util.buttonLabel
import dev.cannoli.scorza.ui.ViewportInsetsPx
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.effectiveViewportPadding
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.PortraitMarginOverlay
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import dev.cannoli.scorza.ui.viewmodel.SettingsKey
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.RommCacheSyncStatus
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.LocalListRhythm
import dev.cannoli.ui.components.LocalUntitledListRhythm
import dev.cannoli.ui.components.bottomBarHeight
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillLineHeightSp
import dev.cannoli.ui.components.pillNominalGap
import dev.cannoli.ui.components.pillScaleFor
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.components.screenTitleMetrics
import dev.cannoli.ui.components.solveListRhythm
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

// RomM brand purple; the screen-edge border shown while browsing RomM.
private val ROMM_BORDER_COLOR = Color(0xFF553E98)
private val ROMM_BORDER_WIDTH = 2.dp

@Composable
private fun RommBorderFrame() {
    Box(modifier = Modifier.fillMaxSize().border(ROMM_BORDER_WIDTH, ROMM_BORDER_COLOR))
}

private const val CACHE_SYNC_INDICATOR_DELAY_MS = 1200L
private const val CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS = 800L

/**
 * Status-bar state for the RomM cache mirror, shown only while browsing RomM. A stale mirror
 * outranks an in-flight sync so a failing retry cannot blink the alert off and back on, and the
 * syncing glyph is withheld until the sync has run long enough to be worth mentioning. Once shown
 * it stays up for a minimum span, so a sync that finishes just past the threshold does not flash.
 */
@Composable
private fun rommCacheSyncIndicator(
    status: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus,
    stale: Boolean,
    inRomm: Boolean,
): RommCacheSyncStatus {
    val syncing = inRomm && status == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING
    var showSyncing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(syncing) {
        if (syncing) {
            delay(CACHE_SYNC_INDICATOR_DELAY_MS)
            shownAt = SystemClock.elapsedRealtime()
            showSyncing = true
        } else if (showSyncing) {
            val held = SystemClock.elapsedRealtime() - shownAt
            if (held < CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS) delay(CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS - held)
            showSyncing = false
        }
    }
    return when {
        !inRomm -> RommCacheSyncStatus.IDLE
        stale -> RommCacheSyncStatus.ERROR
        showSyncing -> RommCacheSyncStatus.SYNCING
        else -> RommCacheSyncStatus.IDLE
    }
}

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel? = null,
    gameListViewModel: GameListViewModel? = null,
    inputTesterViewModel: InputTesterViewModel,
    onExitInputTester: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    controllersViewModel: ControllersViewModel,
    dialogState: StateFlow<DialogState>,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    resumableGames: Set<String> = emptySet(),
    updateAvailable: Boolean = false,
    downloadProgress: Float = 0f,
    coreUpdate: dev.cannoli.scorza.launcher.CoreDownloadService.UpdateProgress? = null,
    downloadError: String? = null,
    osdController: dev.cannoli.ui.components.OsdController,
    activeMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController? = null,
    legendWizardState: dev.cannoli.scorza.input.legend.LegendWizardState = dev.cannoli.scorza.input.legend.LegendWizardState(),
    onboardingMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    onboardingConfirmPresses: Int = 0,
    onOnboardingRunExpired: () -> Unit = {},
    nav: dev.cannoli.scorza.navigation.NavigationController? = null,
    inputRouter: dev.cannoli.scorza.input.InputRouter? = null,
    rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel? = null,
    rommImageLoader: coil.ImageLoader? = null,
    rommHost: String = "",
    rommArtType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    rommDownloader: dev.cannoli.scorza.download.Downloader? = null,
    rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher? = null,
    saveSyncStatus: dev.cannoli.ui.components.SaveSyncStatus = dev.cannoli.ui.components.SaveSyncStatus.DISABLED,
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val pillScale = pillScaleFor(appSettings.textSize.sp)
    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = pillLineHeightSp(appSettings.textSize.sp).sp

    val labels = dev.cannoli.ui.ButtonStyle(
        activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        activeMapping.confirmButton(),
    )

    val labelContext = androidx.compose.ui.platform.LocalContext.current
    val shortcutKeyLabel: (Int) -> String = { keyCode ->
        buttonLabel(
            labelContext,
            keyCode,
            activeMapping,
            activeMapping?.glyphStyle ?: dev.cannoli.scorza.input.GlyphStyle.PLUMBER,
        )
    }

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent,
        title = appSettings.colorTitle,
        background = appSettings.colorBackground,
        statusBar = appSettings.colorStatusBar
    )

    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val scaleFactor = appSettings.textSize.sp / 22f
    val cannoliTypography = buildCannoliTypography(baseSizeSp = appSettings.textSize.sp, fontFamily = LocalCannoliFont.current)

    val viewportInsets = ViewportInsetsPx(
        geometryWidthPct = appSettings.screenGeometryWidth,
        geometryHeightPct = appSettings.screenGeometryHeight,
        geometryXPct = appSettings.screenGeometryX,
        geometryYPct = appSettings.screenGeometryY,
        portraitMarginPx = appSettings.portraitMarginPx,
    )
    CompositionLocalProvider(
        LocalCannoliColors provides cannoliColors,
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides scaleFactor,
        LocalCannoliTypography provides cannoliTypography,
        LocalViewportInsets provides viewportInsets,
        LocalPillScale provides pillScale
    ) {
    // Must resolve inside the provider so they match what PillRow actually renders.
    val listVerticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val settingsState = settingsViewModel.state.collectAsState().value
    // The margin preview marks the band the game will NOT draw into, so it has to measure the whole
    // screen. Drawn inside the padded box below it would measure an area the margin has already been
    // subtracted from, and at a large margin the band collapses to cover everything.
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == SettingsCategory.DISPLAY
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == SettingsKey.PORTRAIT_MARGIN.id
    Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().displayCutoutPadding().padding(effectiveViewportPadding())) {
    // Solved once here so the title spacer, the row spacing and the footer reservation all come from
    // the same division of the screen; every list screen lays out inside these same bounds.
    val insets = screenInsets()
    // Every fixed height below is laid out as whole pixels, so the solve has to round the same way
    // or its row count drifts from the one Compose actually places.
    val density = LocalDensity.current
    fun Dp.snap(): Dp = with(density) { roundToPx().toDp() }
    val available = (maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()).snap()
    val titleMetrics = screenTitleMetrics(listFontSize, listLineHeight)
    val barHeight = bottomBarHeight().snap()
    val rowHeight = itemHeight.snap()
    val pixel = with(density) { 1.toDp() }
    val titleHeight = titleMetrics.height.snap()
    // What each end needs beyond the shared row spacing for the gaps to read alike: under the title,
    // the row's own below-ink space less the title's deeper descent; over the footer, the row's
    // above-ink leading, which the legend pill's solid edge has no counterpart for.
    val edge = pillNominalGap() / 2
    val topExtra = titleMetrics.rowBelowInk + edge - titleMetrics.titleBelowInk
    val bottomExtra = titleMetrics.rowAboveInk + edge
    val rhythm =
        solveListRhythm(
            available = available, titleHeight = titleHeight, barHeight = barHeight,
            rowHeight = rowHeight, topExtra = topExtra, bottomExtra = bottomExtra,
            titled = true, pixel = pixel,
        )
    val untitledRhythm =
        solveListRhythm(
            available = available, titleHeight = titleHeight, barHeight = barHeight,
            rowHeight = rowHeight, topExtra = topExtra, bottomExtra = bottomExtra,
            titled = false, pixel = pixel,
        )
    CompositionLocalProvider(
        LocalListRhythm provides rhythm,
        LocalUntitledListRhythm provides untitledRhythm
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            is LauncherScreen.SystemList -> {
                if (systemListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.systemListHandler) }
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    title = appSettings.title,
                    mainMenuQuit = appSettings.mainMenuQuit,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    fiveGameHandheld = appSettings.contentMode == dev.cannoli.scorza.settings.ContentMode.FIVE_GAME_HANDHELD,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.GameList -> {
                if (gameListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.gameListHandler) }
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.InputTester -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.inputTesterHandler) }
                InputTesterScreen(
                    viewModel = inputTesterViewModel,
                    buttonStyle = labels,
                    onExit = onExitInputTester,
                )
            }
            is LauncherScreen.Settings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.settingsHandler) }
                SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
            }
            else -> {}
        }
        EmulatorScreens(
            currentScreen = currentScreen,
            onListStateChanged = onListStateChanged,
            inputRouter = inputRouter,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            cannoliColors = cannoliColors,
            listVerticalPadding = listVerticalPadding,
            itemHeight = itemHeight,
        )
        PickerScreens(
            currentScreen = currentScreen,
            onListStateChanged = onListStateChanged,
            inputRouter = inputRouter,
            dialog = dialog,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            cannoliColors = cannoliColors,
            listVerticalPadding = listVerticalPadding,
            itemHeight = itemHeight,
        )
        InputScreens(
            currentScreen = currentScreen,
            controllersViewModel = controllersViewModel,
            onListStateChanged = onListStateChanged,
            editButtonsController = editButtonsController,
            legendWizardState = legendWizardState,
            nav = nav,
            inputRouter = inputRouter,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            shortcutKeyLabel = shortcutKeyLabel,
            listVerticalPadding = listVerticalPadding,
            itemHeight = itemHeight,
        )
        AppScreens(
            currentScreen = currentScreen,
            onListStateChanged = onListStateChanged,
            onboardingMapping = onboardingMapping,
            onboardingConfirmPresses = onboardingConfirmPresses,
            onOnboardingRunExpired = onOnboardingRunExpired,
            inputRouter = inputRouter,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            listVerticalPadding = listVerticalPadding,
            itemHeight = itemHeight,
        )
        GameScreens(
            currentScreen = currentScreen,
            inputRouter = inputRouter,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            listVerticalPadding = listVerticalPadding,
        )
        RommScreens(
            currentScreen = currentScreen,
            onListStateChanged = onListStateChanged,
            nav = nav,
            inputRouter = inputRouter,
            rommBrowseViewModel = rommBrowseViewModel,
            rommImageLoader = rommImageLoader,
            rommHost = rommHost,
            rommArtType = rommArtType,
            rommDownloader = rommDownloader,
            appSettings = appSettings,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            labels = labels,
            listVerticalPadding = listVerticalPadding,
        )

        // Hoisted full-screen dialog rendering: every screen gets the keyboard / full-screen
        // overlays for free, so a new screen can never silently capture input with nothing drawn.
        val overlayDownloads = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
        if (dialog is DialogState.RommDownloads && overlayDownloads.isEmpty()) {
            androidx.compose.runtime.LaunchedEffect(Unit) { nav?.dialogState?.value = DialogState.None }
        }
        val artState = rommArtFetcher?.state?.collectAsState()?.value
        androidx.compose.runtime.LaunchedEffect(artState) {
            val finished = artState as? dev.cannoli.scorza.romm.art.ArtFetchState.Finished
            if (finished != null && dialog !is DialogState.RommArtResults) {
                nav?.dialogState?.value = DialogState.RommArtResults(finished.results)
            }
        }
        // No allowlist gate here on purpose: DialogOverlay's own when decides what it draws and
        // falls through to nothing for states a screen renders itself. A second list to keep in
        // sync is how a dialog ends up set and consuming input while drawing nothing.
        DialogOverlay(
            dialogState = dialog,
            backgroundImagePath = appSettings.backgroundImagePath,
            backgroundTint = appSettings.backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            downloadProgress = downloadProgress,
            coreUpdate = coreUpdate,
            downloadError = downloadError,
            downloads = overlayDownloads,
            updateAvailable = updateAvailable,
            buttonStyle = labels,
            appListPlatformTag = gameListViewModel?.state?.collectAsState()?.value?.platformTag,
        )

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is DialogState.UpdateDownload
                || dialog is DialogState.Launching
                || dialog is DialogState.SaveSyncChecking
                || dialog is KeyboardHost
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.CreditsSection
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.Guide
                || currentScreen is LauncherScreen.InputTester
                || currentScreen is LauncherScreen.OnboardingScreen
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val showKitchenIcon = dev.cannoli.scorza.server.KitchenManager.running.collectAsState().value
                && appSettings.showKitchen
        val artRunning = appSettings.showDownloads &&
                rommArtFetcher?.state?.collectAsState()?.value == dev.cannoli.scorza.romm.art.ArtFetchState.Running
        val activeDownloadCount = if (appSettings.showDownloads) {
            rommDownloader?.state?.collectAsState()?.value?.count {
                it.status == dev.cannoli.scorza.download.DownloadStatus.Queued || it.status is dev.cannoli.scorza.download.DownloadStatus.Downloading
            } ?: 0
        } else 0
        val inRomm = currentScreen is LauncherScreen.RommPlatformList ||
                currentScreen is LauncherScreen.RommGameList ||
                currentScreen is LauncherScreen.RommCollectionGroups ||
                currentScreen is LauncherScreen.RommVirtualTypes ||
                currentScreen is LauncherScreen.RommCollectionList ||
                currentScreen is LauncherScreen.RommCollectionGameList ||
                currentScreen is LauncherScreen.RommGlobalSearch ||
                currentScreen is LauncherScreen.RommFirmwareList ||
                currentScreen is LauncherScreen.RommGameDetail
        val cacheSyncStatus = rommCacheSyncIndicator(
            status = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                ?: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.IDLE,
            stale = rommBrowseViewModel?.syncStale?.collectAsState()?.value ?: false,
            inRomm = inRomm,
        )
        val hasContent = showKitchenIcon
                || activeDownloadCount > 0
                || artRunning
                || cacheSyncStatus != RommCacheSyncStatus.IDLE
                || appSettings.showWifi
                || appSettings.showBluetooth
                || appSettings.showVpn
                || appSettings.showClock
                || appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE
                || (updateAvailable && appSettings.showUpdate)
        if (!hideForDialog && !hideForScreen && hasContent) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(screenInsets())
                .onGloballyPositioned { coords ->
                    statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                }
        ) {
            StatusBar(
                updateAvailable = updateAvailable,
                kitchenRunning = showKitchenIcon,
                downloadCount = activeDownloadCount,
                downloadsActive = artRunning,
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showVpn = appSettings.showVpn,
                showClock = appSettings.showClock,
                showBattery = appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE,
                batteryIconOnly = appSettings.batteryDisplay == dev.cannoli.scorza.settings.BatteryDisplay.ICON,
                showUpdate = appSettings.showUpdate,
                use24hTime = appSettings.use24h,
                saveSyncStatus = saveSyncStatus,
                rommCacheSyncStatus = cacheSyncStatus,
            )
        }
        }
        if (inRomm) RommBorderFrame()
    }
    val onScreenGeometryRow = currentScreen is LauncherScreen.Settings && settingsState.activeCategory == SettingsCategory.SCREEN_GEOMETRY
    val geometryIsDefault = appSettings.screenGeometryWidth == 100 && appSettings.screenGeometryHeight == 100 &&
        appSettings.screenGeometryX == 0 && appSettings.screenGeometryY == 0
    if (onScreenGeometryRow && !geometryIsDefault) {
        Box(modifier = Modifier.fillMaxSize().border(2.dp, cannoliColors.accent))
    }
    OsdHost(controller = osdController)
    }
    }
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    }
    }
}
