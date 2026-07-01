package dev.cannoli.scorza.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.scorza.ui.LocalPortraitMargin
import dev.cannoli.scorza.util.keyCodeName
import dev.cannoli.scorza.ui.PortraitMarginState
import dev.cannoli.scorza.ui.components.CREDITS
import dev.cannoli.scorza.ui.components.CreditsOverlay
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.effectivePortraitMarginDp
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.ControllerDetailScreen
import dev.cannoli.scorza.ui.screens.ControllersScreen
import dev.cannoli.scorza.ui.screens.EditButtonsScreen
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import dev.cannoli.scorza.ui.screens.EmulatorPickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.LoggingSettingsScreen
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.PortraitMarginOverlay
import dev.cannoli.scorza.ui.screens.SaveSlotsScreen
import dev.cannoli.scorza.ui.screens.SaveStatePickerScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.screens.isFullScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.SectionHeader
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.flow.StateFlow

enum class BrowsePurpose { SD_ROOT, ROM_DIRECTORY, SETUP }

enum class OnboardingPermission { STORAGE }

// Nerd Font md-alert glyph; flags a mapped emulator we can confirm is not installed.
private const val ICON_NOT_INSTALLED = "\uDB80\uDC26"

// RomM brand purple; the screen-edge border shown while browsing RomM.
private val ROMM_BORDER_COLOR = Color(0xFF553E98)
private val ROMM_BORDER_WIDTH = 2.dp

@Composable
private fun RommBorderFrame() {
    Box(modifier = Modifier.fillMaxSize().border(ROMM_BORDER_WIDTH, ROMM_BORDER_COLOR))
}

sealed class LauncherScreen {
    interface ScrollableScreen {
        val selectedIndex: Int
        val scrollTarget: Int
        val itemCount: Int
        fun withScroll(selectedIndex: Int, scrollTarget: Int): LauncherScreen
    }

    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    data object Settings : LauncherScreen()
    data object InputTester : LauncherScreen()
    data class SaveStatePicker(
        val rom: dev.cannoli.scorza.model.Rom,
        val stateBasePath: String,
        val slotOccupied: List<Boolean>,
        val selectedSlotIndex: Int,
        val awaitConfirmRelease: Boolean = false,
    ) : LauncherScreen()
    data class SaveSlots(
        val gameKey: String,
        val tag: String,
        val base: String,
        val romId: Int,
        val emulator: String?,
        val slots: List<dev.cannoli.scorza.romm.sync.SlotInfo>,
        val selectedIndex: Int = 0,
        val pendingDelete: Boolean = false,
    ) : LauncherScreen()
    data class EmulatorMapping(val mappings: List<EmulatorMappingEntry>, val allMappings: List<EmulatorMappingEntry> = mappings, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val filter: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = mappings.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class EmulatorPicker(val tag: String, val platformName: String, val cores: List<EmulatorPickerOption>, override val selectedIndex: Int = 0, val gamePath: String? = null, override val scrollTarget: Int = 0, val activeIndex: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = cores.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class PlatformMapping(
        val tag: String,
        val platformName: String,
        val items: List<dev.cannoli.scorza.ui.screens.MappingItem>,
        val showAll: Boolean = false,
        val overridesCount: Int = 0,
        val resettable: Boolean = false,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        val selectableItems: List<dev.cannoli.scorza.ui.screens.MappingItem>
            get() = items.filter { it.isSelectable }
        override val itemCount: Int get() = selectableItems.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class BiosStatus(
        val tag: String,
        val platformName: String,
        val coreDisplayName: String,
        val runnerLabel: String,
        val firmware: List<dev.cannoli.scorza.ui.screens.FirmwareStatus>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class PlatformOverrides(
        val tag: String,
        val platformName: String,
        val overrides: List<Pair<String, String>>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int = overrides.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ColorList(val colors: List<ColorEntry>, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = colors.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = apps.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ChildPicker(val parentId: Long, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Controllers(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ControllerDetail(
        val mappingId: String,
        val androidDeviceId: Int? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 5
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class EditButtons(
        val mappingId: String,
        val listeningCanonical: dev.cannoli.scorza.input.CanonicalButton? = null,
        val countdownMs: Int = 0,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.input.CanonicalButton.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class LoggingSettings(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.util.LoggingPrefs.Category.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ShortcutBinding(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(), val listening: Boolean = false, val heldKeys: Set<Int> = emptySet(), val countdownMs: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = ShortcutAction.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Credits(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = CREDITS.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommPlatformList(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGroups(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommVirtualTypes(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionList(
        val group: dev.cannoli.scorza.romm.RommCollectionGroup,
        val virtualType: String? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGameList(
        val collection: dev.cannoli.scorza.romm.RommCollection,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGlobalSearch(
        val term: String,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommFirmwareList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val rows: List<dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.RommFirmwareRow> = emptyList(),
        val checkedIds: Set<Int> = emptySet(),
        val loading: Boolean = true,
        val error: Boolean = false,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = rows.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameDetail(
        val game: dev.cannoli.scorza.romm.RommGame,
        val localState: dev.cannoli.scorza.romm.LocalState,
        val platformName: String,
        val tag: String,
        val scrollStep: Int = 0,
        val members: List<dev.cannoli.scorza.romm.RommGame> = listOf(game),
    ) : LauncherScreen()
    data class RaOfflinePlatform(val tag: String, val name: String, val count: Int)

    data class RetroAchievementsOfflinePlatforms(
        val platforms: List<RaOfflinePlatform> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = platforms.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RetroAchievementsOfflineSets(
        val platformTag: String = "",
        val platformName: String = "",
        val entries: List<dev.cannoli.scorza.ra.RaOfflineStore.Entry> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class InstalledCores(val cores: List<String> = emptyList(), val loading: Boolean = true, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val title: String? = null) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = cores.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class DirectoryBrowser(
        val purpose: BrowsePurpose,
        val currentPath: String,
        val entries: List<String> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size + if (currentPath != "/storage/") 1 else 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class OnboardingPermissions(
        val permissions: List<OnboardingPermission>,
        val granted: Set<OnboardingPermission>,
        val volumes: List<Pair<String, String>> = emptyList(),
        val volumeIndex: Int = 0,
        val customPath: String? = null,
        val selectedIndex: Int = 0,
        val existingInstallVolumeIndex: Int? = null,
    ) : LauncherScreen() {
        val allGranted: Boolean get() = granted.containsAll(permissions)
        val storageRowIndex: Int get() = permissions.size
        val continueRowIndex: Int get() = storageRowIndex + 1
        val focusableCount: Int
            get() = permissions.size + (if (allGranted) 1 else 0) + (if (continueEnabled) 1 else 0)
        val isStorageRowFocused: Boolean get() = allGranted && selectedIndex == storageRowIndex
        val isContinueRowFocused: Boolean get() = continueEnabled && selectedIndex == continueRowIndex
        val focusedPermission: OnboardingPermission?
            get() = if (selectedIndex in permissions.indices) permissions[selectedIndex] else null
        val isFocusedGranted: Boolean get() = focusedPermission?.let { it in granted } ?: false
        val selectedVolume: Pair<String, String>? get() = volumes.getOrNull(volumeIndex)
        val isCustomVolume: Boolean get() = selectedVolume?.first == "Custom"
        val continueEnabled: Boolean
            get() = allGranted && volumes.isNotEmpty() && (!isCustomVolume || customPath != null)
        val targetPath: String?
            get() {
                if (!continueEnabled) return null
                return if (isCustomVolume) customPath else selectedVolume!!.second + "Cannoli/"
            }
        fun moved(delta: Int) = copy(
            selectedIndex = (selectedIndex + delta).coerceIn(0, (focusableCount - 1).coerceAtLeast(0))
        )
        fun cycledVolume(delta: Int): OnboardingPermissions {
            if (volumes.size <= 1) return this
            val next = ((volumeIndex + delta) % volumes.size + volumes.size) % volumes.size
            return copy(volumeIndex = next, customPath = null)
        }
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
    downloadError: String? = null,
    osdController: dev.cannoli.ui.components.OsdController,
    activeMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    mappingRepository: dev.cannoli.scorza.input.repo.MappingRepository? = null,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController? = null,
    nav: dev.cannoli.scorza.navigation.NavigationController? = null,
    inputRouter: dev.cannoli.scorza.input.InputRouter? = null,
    rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel? = null,
    rommImageLoader: coil.ImageLoader? = null,
    rommHost: String = "",
    rommArtType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader? = null,
    rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher? = null,
    saveSyncStatus: dev.cannoli.ui.components.SaveSyncStatus = dev.cannoli.ui.components.SaveSyncStatus.DISABLED,
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = (appSettings.textSize.sp + 10).sp
    val listVerticalPadding = 6.dp

    val labels = dev.cannoli.ui.ButtonStyle(
        activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        activeMapping.confirmButton(),
    )

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent,
        title = appSettings.colorTitle,
        background = appSettings.colorBackground,
        statusBar = appSettings.colorStatusBar
    )

    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val scaleFactor = appSettings.textSize.sp / 22f
    val cannoliTypography = buildCannoliTypography(baseSizeSp = appSettings.textSize.sp, fontFamily = LocalCannoliFont.current)

    val portraitMarginState = PortraitMarginState(marginPx = appSettings.portraitMarginPx)
    CompositionLocalProvider(
        LocalCannoliColors provides cannoliColors,
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides scaleFactor,
        LocalCannoliTypography provides cannoliTypography,
        LocalPortraitMargin provides portraitMarginState
    ) {
    Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = effectivePortraitMarginDp())) {
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
                    dialogState = dialog,
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
                    dialogState = dialog,
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
            is LauncherScreen.EmulatorMapping -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val filterLabel = when (currentScreen.filter) {
                    1 -> stringResource(R.string.filter_missing)
                    2 -> stringResource(R.string.filter_unmapped)
                    3 -> stringResource(R.string.filter_mapped)
                    else -> stringResource(R.string.filter_all)
                }
                val selected = currentScreen.mappings.getOrNull(currentScreen.selectedIndex)
                val canSelect = selected != null
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_emulator_mapping),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    leftBottomItems = listOf(labels.west to filterLabel),
                    rightBottomItems = buildList {
                        if (canSelect) add(labels.confirm to stringResource(R.string.label_select))
                    },
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.mappings,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        val value = when {
                            entry.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NEEDS_SETUP -> stringResource(R.string.value_unmapped)
                            entry.runnerLabel.isEmpty() -> entry.coreDisplayName
                            else -> "${entry.coreDisplayName} (${entry.runnerLabel})"
                        }
                        val valueIcon = if (entry.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED) {
                            ICON_NOT_INSTALLED
                        } else null
                        PillRowKeyValue(
                            label = entry.platformName,
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            valueIcon = valueIcon
                        )
                    }
                }
            }
            is LauncherScreen.EmulatorPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platformName,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    if (currentScreen.cores.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_compatible_cores),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.cores,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, option, isSelected ->
                            val label = if (option.runnerLabel.isEmpty()) option.displayName
                                else "${option.displayName} (${option.runnerLabel})"
                            if (index == currentScreen.activeIndex) {
                                PillRowKeyValue(
                                    label = label,
                                    value = stringResource(R.string.value_active),
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            } else {
                                PillRowText(
                                    label = label,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            }
                        }
                    }
                }
            }
            is LauncherScreen.PlatformMapping -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val selectableIndices = remember(currentScreen.items) {
                    currentScreen.items.mapIndexedNotNull { idx, it -> if (it.isSelectable) idx else null }
                }
                val highlightedIndex = selectableIndices.getOrNull(
                    currentScreen.selectedIndex.coerceIn(0, (selectableIndices.size - 1).coerceAtLeast(0))
                ) ?: -1
                val yLabel = if (currentScreen.showAll)
                    stringResource(R.string.label_show_installed)
                else
                    stringResource(R.string.label_show_all)
                val highlighted = currentScreen.items.getOrNull(highlightedIndex)
                val confirmLabel = if ((highlighted as? dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption)?.downloadable == true)
                    stringResource(R.string.label_download)
                else
                    stringResource(R.string.label_select)
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_platform_mapping, currentScreen.platformName),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        add(labels.north to yLabel)
                        if (highlightedIndex >= 0) add(labels.confirm to confirmLabel)
                    },
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.items,
                        selectedIndex = highlightedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, item, isSelected ->
                        when (item) {
                            is dev.cannoli.scorza.ui.screens.MappingItem.SectionHeader -> SectionHeader(
                                text = item.label,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            is dev.cannoli.scorza.ui.screens.MappingItem.Divider -> Spacer(
                                modifier = Modifier.height(listVerticalPadding * 2)
                            )
                            is dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption -> {
                                val opt = item.option
                                val value = when {
                                    item.isCurrent -> stringResource(R.string.value_active)
                                    !opt.available -> {
                                        val resId = when (opt.runnerLabel) {
                                            "Internal" -> R.string.value_not_bundled
                                            else -> R.string.value_not_installed
                                        }
                                        stringResource(resId)
                                    }
                                    else -> ""
                                }
                                PillRowKeyValue(
                                    label = opt.displayName,
                                    value = value,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding,
                                    valueIcon = if (item.isCurrent && !opt.available) ICON_NOT_INSTALLED else null
                                )
                            }
                            is dev.cannoli.scorza.ui.screens.MappingItem.Action -> {
                                val value = item.status
                                if (item.statusIsWarning) {
                                    PillRowKeyValue(
                                        label = item.label,
                                        value = value,
                                        isSelected = isSelected,
                                        fontSize = listFontSize,
                                        lineHeight = listLineHeight,
                                        verticalPadding = listVerticalPadding,
                                        valueIcon = ICON_NOT_INSTALLED
                                    )
                                } else {
                                    PillRowKeyValue(
                                        label = item.label,
                                        value = value,
                                        isSelected = isSelected,
                                        fontSize = listFontSize,
                                        lineHeight = listLineHeight,
                                        verticalPadding = listVerticalPadding
                                    )
                                }
                            }
                        }
                    }
                }
                when (dialog) {
                    is DialogState.PlatformResetConfirm -> ConfirmOverlay(
                        message = stringResource(
                            R.string.dialog_reset_platform_confirm,
                            (dialog as DialogState.PlatformResetConfirm).platformName
                        ),
                        confirmLabel = stringResource(R.string.label_reset),
                        buttonStyle = labels
                    )
                    else -> {}
                }
            }
            is LauncherScreen.BiosStatus -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_platform_bios, currentScreen.platformName),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "${currentScreen.coreDisplayName} · ${currentScreen.runnerLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cannoliColors.accent,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                        if (currentScreen.firmware.isEmpty()) {
                            Text(
                                text = stringResource(R.string.value_no_firmware),
                                style = MaterialTheme.typography.bodyLarge,
                                color = cannoliColors.text.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 14.dp, top = 6.dp)
                            )
                        } else {
                            List(
                                items = currentScreen.firmware,
                                selectedIndex = -1,
                                itemHeight = itemHeight,
                                scrollTarget = currentScreen.scrollTarget,
                                onListStateChanged = onListStateChanged,
                                modifier = Modifier.weight(1f)
                            ) { _, fw, _ ->
                                val required = !fw.entry.optional
                                val tag = stringResource(if (required) R.string.bios_required else R.string.bios_optional)
                                val statusText = stringResource(if (fw.present) R.string.bios_present else R.string.bios_missing)
                                val requiredMissing = required && !fw.present
                                val rowColor = if (!fw.present && !required) cannoliColors.text.copy(alpha = 0.5f) else cannoliColors.text
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = listVerticalPadding)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = fw.entry.path,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontSize = listFontSize
                                            ),
                                            color = rowColor,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = cannoliColors.accent.copy(alpha = 0.8f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (requiredMissing) {
                                            Text(
                                                text = ICON_NOT_INSTALLED,
                                                fontFamily = dev.cannoli.ui.theme.LocalCannoliIconFont.current,
                                                fontSize = listFontSize,
                                                color = cannoliColors.text
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = rowColor
                                        )
                                    }
                                    Text(
                                        text = fw.entry.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cannoliColors.text.copy(alpha = 0.55f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.PlatformOverrides -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platformName,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        if (currentScreen.overrides.isNotEmpty()) add(labels.north to stringResource(R.string.label_clear_override))
                    },
                    buttonStyle = labels
                ) {
                    if (currentScreen.overrides.isEmpty()) {
                        Text(
                            text = stringResource(R.string.value_no_overrides),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.overrides,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { _, item, isSelected ->
                            val romName = java.io.File(item.first).nameWithoutExtension
                            PillRowKeyValue(
                                label = romName,
                                value = item.second,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.ColorList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_colors),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.colors,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        PillRowKeyValue(
                            label = stringResource(entry.labelRes),
                            value = entry.hex.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = Color(entry.color.toInt())
                        )
                    }
                }
            }
            is LauncherScreen.CollectionPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    leftBottomItems = listOf(
                        labels.west to stringResource(R.string.label_new)
                    ),
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
                val d = dialog
                if (d is DialogState.CollectionCreated) {
                    MessageOverlay(message = stringResource(R.string.collection_created, d.collectionName), buttonStyle = labels)
                }
            }
            is LauncherScreen.ChildPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_child_collections),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
            }
            is LauncherScreen.AppPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.apps,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { index, app, isSelected ->
                        PillRowText(
                            label = app,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in currentScreen.checkedIndices
                        )
                    }
                }
            }
            is LauncherScreen.ShortcutBinding -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_shortcuts),

                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = if (currentScreen.listening) listOf("" to stringResource(R.string.label_hold_buttons))
                        else listOf(labels.north to stringResource(R.string.label_clear), labels.confirm to stringResource(R.string.label_set)),
                    buttonStyle = labels
                ) {
                    List(
                        items = ShortcutAction.entries.toList(),
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, action, isSelected ->
                        val chord = currentScreen.shortcuts[action]
                        val value = if (chord.isNullOrEmpty()) stringResource(R.string.value_none)
                        else chord.joinToString(" + ") { keyCodeName(it) }
                        PillRowKeyValue(
                            label = stringResource(action.labelRes),
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
                if (currentScreen.listening) {
                    val colors = LocalCannoliColors.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        ) {
                            val actionName = ShortcutAction.entries.getOrNull(currentScreen.selectedIndex)
                                ?.let { stringResource(it.labelRes) } ?: ""
                            Text(
                                text = actionName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 24.sp,
                                    color = colors.text
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Sm))
                            Text(
                                text = if (currentScreen.heldKeys.isEmpty()) stringResource(R.string.shortcut_hold_prompt)
                                else currentScreen.heldKeys.joinToString(" + ") { keyCodeName(it) },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 16.sp,
                                    color = colors.text.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Lg))
                            if (currentScreen.heldKeys.isNotEmpty()) {
                                val progress = (currentScreen.countdownMs / 1500f).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp).fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(Radius.Sm))
                                        .background(colors.text.copy(alpha = 0.2f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progress)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(Radius.Sm))
                                            .background(colors.highlight)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.InstalledCores -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title ?: stringResource(R.string.title_installed_cores),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.loading) {
                        // wait for broadcast response
                    } else if (currentScreen.cores.isEmpty()) {
                        Text(
                            text = stringResource(R.string.installed_cores_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.cores,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { _, core, isSelected ->
                            PillRowText(
                                label = core,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingHandler) }
                DirectoryBrowserScreen(
                    currentPath = currentScreen.currentPath,
                    entries = currentScreen.entries,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    isSelectRow = currentScreen.selectedIndex == 0,
                    showSelectOption = currentScreen.currentPath != "/storage/",
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels
                )
            }
            is LauncherScreen.Credits -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                CreditsOverlay(
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.Controllers -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllersHandler) }
                ControllersScreen(
                screen = currentScreen,
                viewModel = controllersViewModel,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.ControllerDetail -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllerDetailHandler) }
                val controllersState by controllersViewModel.state.collectAsState()
                val mapping = controllersState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: controllersState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                ControllerDetailScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.EditButtons -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.editButtonsHandler) }
                val editState by controllersViewModel.state.collectAsState()
                val mapping = editState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: editState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                    ?: mappingRepository?.findById(currentScreen.mappingId)
                if (editButtonsController != null && nav != null) {
                    androidx.compose.runtime.LaunchedEffect(currentScreen.listeningCanonical) {
                        if (currentScreen.listeningCanonical != null) {
                            val startedAt = System.currentTimeMillis()
                            while (currentScreen.listeningCanonical != null) {
                                kotlinx.coroutines.delay(50)
                                val finalized = editButtonsController.tickAndMaybeFinalize()
                                if (finalized != null || !editButtonsController.isListening) {
                                    val cs = nav.currentScreen
                                    if (cs is LauncherScreen.EditButtons) {
                                        nav.replaceTop(cs.copy(listeningCanonical = null, countdownMs = 0))
                                    }
                                    break
                                }
                                val cs = nav.currentScreen
                                if (cs is LauncherScreen.EditButtons && cs.listeningCanonical != null) {
                                    val elapsed = (System.currentTimeMillis() - startedAt).toInt()
                                    if (cs.countdownMs != elapsed) {
                                        nav.replaceTop(cs.copy(countdownMs = elapsed))
                                    }
                                }
                            }
                        }
                    }
                }
                EditButtonsScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.LoggingSettings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.loggingSettingsHandler) }
                LoggingSettingsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RetroAchievementsOfflinePlatforms -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                dev.cannoli.scorza.ui.screens.RetroAchievementsOfflinePlatformsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged,
                )
            }
            is LauncherScreen.RetroAchievementsOfflineSets -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                dev.cannoli.scorza.ui.screens.RetroAchievementsOfflineSetsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged,
                )
            }
            is LauncherScreen.OnboardingPermissions -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingHandler) }
                dev.cannoli.scorza.ui.screens.OnboardingPermissionsScreen(
                    permissions = currentScreen.permissions,
                    granted = currentScreen.granted,
                    volumes = currentScreen.volumes,
                    volumeIndex = currentScreen.volumeIndex,
                    customPath = currentScreen.customPath,
                    selectedIndex = currentScreen.selectedIndex,
                    existingInstallVolumeIndex = currentScreen.existingInstallVolumeIndex,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.SaveStatePicker -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveStatePickerHandler) }
                SaveStatePickerScreen(
                    rom = currentScreen.rom,
                    stateBasePath = currentScreen.stateBasePath,
                    slotOccupied = currentScreen.slotOccupied,
                    selectedSlotIndex = currentScreen.selectedSlotIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.SaveSlots -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveSlotsHandler) }
                SaveSlotsScreen(
                    slots = currentScreen.slots,
                    selectedIndex = currentScreen.selectedIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    pendingDelete = currentScreen.pendingDelete,
                )
            }
            is LauncherScreen.RommPlatformList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val platforms = rommBrowseViewModel?.platforms?.collectAsState()?.value ?: emptyList()
                val collections = rommBrowseViewModel?.collections?.collectAsState()?.value ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    rommBrowseViewModel?.enterBrowse()
                }
                val showCollectionsRow = collections.isNotEmpty()
                val syncStatus = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                val syncProgress = rommBrowseViewModel?.syncProgress?.collectAsState()?.value
                var emptyMessage: String? = null
                var syncFraction: Float? = null
                if (platforms.isEmpty()) when (syncStatus) {
                    dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING ->
                        if (syncProgress != null && syncProgress.total > 0) {
                            val platformName = syncProgress.platform
                            emptyMessage = if (platformName != null)
                                androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing_platform, platformName)
                            else androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                            syncFraction = syncProgress.completed.toFloat() / syncProgress.total
                        } else {
                            emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                        }
                    dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.ERROR ->
                        emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_sync_error)
                    else -> {}
                }
                val effectiveItemCount = platforms.size + (if (showCollectionsRow) 1 else 0)
                androidx.compose.runtime.LaunchedEffect(effectiveItemCount) {
                    if (currentScreen.itemCount != effectiveItemCount) nav?.replaceTop(currentScreen.copy(itemCount = effectiveItemCount))
                }
                dev.cannoli.scorza.ui.screens.RommPlatformListScreen(
                    platforms = platforms,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    showCollectionsRow = showCollectionsRow,
                    collectionCount = collections.size,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                    emptyMessage = emptyMessage,
                    progress = syncFraction,
                    syncing = syncStatus == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING,
                )
            }
            is LauncherScreen.RommGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.games?.collectAsState()?.value
                // null (not loaded), a different platform's id, or a stale search term means the rows are not
                // ours yet, so we render a loading blank rather than flashing the previous list/art or "No results".
                val loading = loaded?.id != currentScreen.platform.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id, currentScreen.search) {
                    rommBrowseViewModel?.openPlatform(currentScreen.platform, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                androidx.compose.runtime.LaunchedEffect(currentScreen.selectedIndex, games.size) {
                    if (!loading && games.isNotEmpty() && currentScreen.selectedIndex >= games.size - 5) rommBrowseViewModel?.loadMore()
                }
                val loader = rommImageLoader
                val queueItems = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
                val doneForPlatform = queueItems.count {
                    it.tag == currentScreen.platform.cannoliTag &&
                        it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(doneForPlatform) {
                    if (doneForPlatform > 0) rommBrowseViewModel?.refreshLocalState()
                }
                val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
                val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = currentScreen.platform.displayName,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        multiSelect = multiSelect,
                        checkedIds = checkedIds,
                        showFirmware = currentScreen.platform.firmwareCount > 0,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommCollectionGroups -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val counts = rommBrowseViewModel?.groupCounts?.collectAsState()?.value ?: emptyMap()
                val enabled = rommBrowseViewModel?.enabledGroups() ?: emptySet()
                val rows = listOf(
                    dev.cannoli.scorza.romm.RommCollectionGroup.USER to stringResource(dev.cannoli.ui.R.string.romm_collections_my),
                    dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL to stringResource(dev.cannoli.ui.R.string.romm_collections_virtual),
                    dev.cannoli.scorza.romm.RommCollectionGroup.SMART to stringResource(dev.cannoli.ui.R.string.romm_collections_smart),
                ).filter { it.first in enabled }
                 .map { dev.cannoli.scorza.ui.screens.RommGroupRow(it.first, it.second, counts[it.first] ?: 0) }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommCollectionGroupsScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommVirtualTypes -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val typeCounts = rommBrowseViewModel?.virtualTypeCounts?.collectAsState()?.value ?: emptyList()
                val rows = typeCounts.map { (type, count) ->
                    val label = dev.cannoli.scorza.romm.RommVirtualType.from(type)?.let { stringResource(it.labelRes) } ?: type
                    dev.cannoli.scorza.ui.screens.RommTypeRow(type, label, count)
                }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommVirtualTypesScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val key = currentScreen.group.name + (currentScreen.virtualType?.let { ":$it" } ?: "")
                val loaded = rommBrowseViewModel?.collectionList?.collectAsState()?.value
                val items = if (loaded?.id == key) loaded.rows else emptyList()
                androidx.compose.runtime.LaunchedEffect(key) {
                    rommBrowseViewModel?.openCollections(currentScreen.group, currentScreen.virtualType)
                }
                androidx.compose.runtime.LaunchedEffect(items.size) {
                    if (currentScreen.itemCount != items.size) nav?.replaceTop(currentScreen.copy(itemCount = items.size))
                }
                val title = currentScreen.virtualType
                    ?.let { vtype ->
                        val typeLabel = dev.cannoli.scorza.romm.RommVirtualType.from(vtype)?.let { t -> stringResource(t.labelRes) } ?: vtype
                        "${stringResource(dev.cannoli.ui.R.string.romm_collection_group_virtual)}: $typeLabel"
                    }
                    ?: stringResource(when (currentScreen.group) {
                        dev.cannoli.scorza.romm.RommCollectionGroup.USER -> dev.cannoli.ui.R.string.romm_collections_my
                        dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> dev.cannoli.ui.R.string.romm_collections_smart
                        dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> dev.cannoli.ui.R.string.romm_collections_virtual
                    })
                dev.cannoli.scorza.ui.screens.RommCollectionListScreen(
                    title = title,
                    collections = items,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.collectionGames?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.collection.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.collection.id, currentScreen.search) {
                    rommBrowseViewModel?.openCollection(currentScreen.collection, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                androidx.compose.runtime.LaunchedEffect(currentScreen.selectedIndex, games.size) {
                    if (!loading && games.isNotEmpty() && currentScreen.selectedIndex >= games.size - 5) rommBrowseViewModel?.loadMoreCollection()
                }
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommCollectionGameListScreen(
                        title = currentScreen.collection.name,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommGlobalSearch -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.searchResults?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.term
                val results = if (loading) emptyList() else loaded?.rows ?: emptyList()
                val allPlatforms = rommBrowseViewModel?.allPlatforms?.collectAsState()?.value ?: emptyList()
                val platformTagById = remember(allPlatforms) { allPlatforms.associate { it.id to it.cannoliTag.uppercase() } }
                androidx.compose.runtime.LaunchedEffect(currentScreen.term) {
                    rommBrowseViewModel?.loadGlobalSearch(dev.cannoli.scorza.romm.RommSearchQuery(currentScreen.term))
                }
                androidx.compose.runtime.LaunchedEffect(loading, results.size) {
                    if (!loading && currentScreen.itemCount != results.size) nav?.replaceTop(currentScreen.copy(itemCount = results.size))
                }
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = stringResource(dev.cannoli.ui.R.string.romm_global_search_title),
                        search = currentScreen.term,
                        games = results,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                        platformLabelForGame = { g -> platformTagById[g.platformId] },
                    )
                }
            }
            is LauncherScreen.RommFirmwareList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id) {
                    if (currentScreen.loading) {
                        val result = runCatching {
                            rommBrowseViewModel?.loadFirmware(currentScreen.platform.id, currentScreen.platform.cannoliTag) ?: emptyList()
                        }
                        nav?.replaceTop(currentScreen.copy(
                            rows = result.getOrDefault(emptyList()),
                            loading = false,
                            error = result.isFailure,
                        ))
                    }
                }
                dev.cannoli.scorza.ui.screens.RommFirmwareListScreen(
                    title = stringResource(dev.cannoli.ui.R.string.romm_firmware_screen_title, currentScreen.platform.displayName),
                    rows = currentScreen.rows,
                    checkedIds = currentScreen.checkedIds,
                    loading = currentScreen.loading,
                    error = currentScreen.error,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommGameDetail -> {
                val loader = rommImageLoader
                val downloads = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
                val downloaded = downloads.any {
                    it.rommId == currentScreen.game.id && it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(downloaded) {
                    if (downloaded && currentScreen.localState != dev.cannoli.scorza.romm.LocalState.PRESENT) {
                        nav?.replaceTop(currentScreen.copy(localState = dev.cannoli.scorza.romm.LocalState.PRESENT))
                    }
                }
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameDetailScreen(
                        game = currentScreen.game,
                        platformName = currentScreen.platformName,
                        localState = currentScreen.localState,
                        host = rommHost,
                        artType = rommArtType,
                        imageLoader = loader,
                        scrollStep = currentScreen.scrollStep,
                        onScrollStepChanged = { nav?.replaceTop(currentScreen.copy(scrollStep = it)) },
                        memberCount = currentScreen.members.size,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        buttonStyle = labels,
                    )
                }
            }
        }

        // Hoisted full-screen dialog rendering: every screen gets the keyboard / full-screen
        // overlays for free, so a new screen can never silently capture input with nothing drawn.
        val overlayDownloads = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
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
        if (dialog.isFullScreen) {
            DialogOverlay(
                dialogState = dialog,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                downloads = overlayDownloads,
                updateAvailable = updateAvailable,
                buttonStyle = labels,
            )
        }

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is DialogState.UpdateDownload
                || dialog is KeyboardHost
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.InputTester
                || currentScreen is LauncherScreen.OnboardingPermissions
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val showKitchenIcon = dev.cannoli.scorza.server.KitchenManager.running.collectAsState().value
                && appSettings.showKitchen
        val artRunning = appSettings.showDownloads &&
                rommArtFetcher?.state?.collectAsState()?.value == dev.cannoli.scorza.romm.art.ArtFetchState.Running
        val activeDownloadCount = if (appSettings.showDownloads) {
            rommDownloader?.queue?.state?.collectAsState()?.value?.count {
                it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Queued || it.status is dev.cannoli.scorza.romm.download.DownloadStatus.Downloading
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
        val hasContent = showKitchenIcon
                || activeDownloadCount > 0
                || artRunning
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
                .padding(screenPadding)
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
            )
        }
        }
        if (inRomm) RommBorderFrame()
    }
    val settingsState = settingsViewModel.state.collectAsState().value
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == "display"
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == "portrait_margin"
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    OsdHost(controller = osdController)
    }
    }
}
