package dev.cannoli.scorza.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.v2.runtime.confirmButton
import dev.cannoli.scorza.input.v2.runtime.labelSet
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.ui.LocalPortraitMargin
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
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.InstallingScreen
import dev.cannoli.scorza.ui.screens.LoggingSettingsScreen
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.ui.screens.PortraitMarginOverlay
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SetupScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.screens.isFullScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OsdPill
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
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
    data class CoreMapping(val mappings: List<CoreMappingEntry>, val allMappings: List<CoreMappingEntry> = mappings, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val filter: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = mappings.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CorePicker(val tag: String, val platformName: String, val cores: List<CorePickerOption>, override val selectedIndex: Int = 0, val gamePath: String? = null, override val scrollTarget: Int = 0, val activeIndex: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = cores.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ColorList(val colors: List<ColorEntry>, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = colors.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collections: List<String>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collections.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = apps.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ChildPicker(val collectionName: String, val collections: List<String>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collections.size
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
        val listeningCanonical: dev.cannoli.scorza.input.v2.CanonicalButton? = null,
        val countdownMs: Int = 0,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.input.v2.CanonicalButton.entries.size
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
    data class Setup(
        val volumes: List<Pair<String, String>> = emptyList(),
        val volumeIndex: Int = 0,
        val selectedIndex: Int = 0,
        val customPath: String? = null
    ) : LauncherScreen()
    data class Installing(
        val targetPath: String,
        val progress: Float = 0f,
        val statusLabel: String = "Kneading the dough$ELLIPSIS",
        val finished: Boolean = false
    ) : LauncherScreen()
    data class Housekeeping(
        val kind: dev.cannoli.scorza.ui.screens.HousekeepingKind,
        val progress: Float = 0f,
        val statusLabel: String = "",
    ) : LauncherScreen()
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
    onVisibleRangeChanged: (firstVisible: Int, visibleCount: Int, isViewportFull: Boolean) -> Unit = { _, _, _ -> },
    resumableGames: Set<String> = emptySet(),
    updateAvailable: Boolean = false,
    downloadProgress: Float = 0f,
    downloadError: String? = null,
    osdMessage: String? = null,
    activeMapping: dev.cannoli.scorza.input.v2.DeviceMapping? = null,
    mappingRepository: dev.cannoli.scorza.input.v2.repo.MappingRepository? = null,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController? = null,
    nav: dev.cannoli.scorza.navigation.NavigationController? = null,
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
        title = appSettings.colorTitle
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
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog,
                    onVisibleRangeChanged = onVisibleRangeChanged,
                    kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
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
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog,
                    onVisibleRangeChanged = onVisibleRangeChanged,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.InputTester -> {
                InputTesterScreen(
                    viewModel = inputTesterViewModel,
                    buttonStyle = labels,
                    onExit = onExitInputTester,
                )
            }
            is LauncherScreen.Settings -> SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                dialogState = dialog,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                updateAvailable = updateAvailable,
                onVisibleRangeChanged = onVisibleRangeChanged,
                buttonStyle = labels,
            )
            is LauncherScreen.CoreMapping -> {
                val filterLabel = when (currentScreen.filter) {
                    1 -> "MISSING"
                    2 -> "INTERNAL"
                    3 -> "EXTERNAL"
                    else -> "ALL"
                }
                val selected = currentScreen.mappings.getOrNull(currentScreen.selectedIndex)
                val canSelect = selected != null
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_core_mapping),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        if (canSelect) add(labels.confirm to stringResource(R.string.label_select))
                        add(labels.west to filterLabel)
                    },
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.mappings,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onVisibleRangeChanged = onVisibleRangeChanged
                    ) { _, entry, isSelected ->
                        val value = if (currentScreen.filter == 0 && entry.runnerLabel.isNotEmpty())
                            "${entry.coreDisplayName} (${entry.runnerLabel})"
                        else entry.coreDisplayName
                        PillRowKeyValue(
                            label = entry.platformName,
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
            is LauncherScreen.CorePicker -> {
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
                            onVisibleRangeChanged = onVisibleRangeChanged
                        ) { index, option, isSelected ->
                            val label = if (option.runnerLabel.isEmpty()) option.displayName
                                else "${option.displayName} (${option.runnerLabel})"
                            if (index == currentScreen.activeIndex) {
                                PillRowKeyValue(
                                    label = label,
                                    value = stringResource(R.string.value_current),
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
            is LauncherScreen.ColorList -> {
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
                        onVisibleRangeChanged = onVisibleRangeChanged
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
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.CollectionPicker -> {
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
                    if (currentScreen.collections.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collections,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onVisibleRangeChanged = onVisibleRangeChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { currentScreen.collections[index] },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
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
                        buttonStyle = labels
                    )
                } else {
                    val d = dialog
                    if (d is DialogState.CollectionCreated) {
                        MessageOverlay(message = stringResource(R.string.collection_created, d.collectionName))
                    }
                }
            }
            is LauncherScreen.ChildPicker -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_child_collections),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collections.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collections,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onVisibleRangeChanged = onVisibleRangeChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { currentScreen.collections[index] },
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
                        onVisibleRangeChanged = onVisibleRangeChanged
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
                        onVisibleRangeChanged = onVisibleRangeChanged
                    ) { _, action, isSelected ->
                        val chord = currentScreen.shortcuts[action]
                        val value = if (chord.isNullOrEmpty()) stringResource(R.string.value_none)
                        else chord.joinToString(" + ") { LibretroInput.keyCodeName(it) }
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
                                else currentScreen.heldKeys.joinToString(" + ") { LibretroInput.keyCodeName(it) },
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
                            onVisibleRangeChanged = onVisibleRangeChanged
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
                    onVisibleRangeChanged = onVisibleRangeChanged,
                    buttonStyle = labels
                )
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.Setup -> {
                val isCustom = currentScreen.volumes.getOrNull(currentScreen.volumeIndex)?.first == "Custom"
                SetupScreen(
                    storageLabel = currentScreen.volumes.getOrNull(currentScreen.volumeIndex)?.first ?: "",
                    selectedIndex = currentScreen.selectedIndex,
                    isCustom = isCustom,
                    customPath = currentScreen.customPath,
                    continueEnabled = !isCustom || currentScreen.customPath != null,
                    buttonStyle = labels
                )
            }
            is LauncherScreen.Installing -> {
                InstallingScreen(
                    progress = currentScreen.progress,
                    statusLabel = currentScreen.statusLabel,
                    finished = currentScreen.finished
                )
            }
            is LauncherScreen.Housekeeping -> {
                dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                    kind = currentScreen.kind,
                    progress = currentScreen.progress,
                    statusLabel = currentScreen.statusLabel,
                )
            }
            is LauncherScreen.Credits -> {
                CreditsOverlay(
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onVisibleRangeChanged = onVisibleRangeChanged
                )
            }
            is LauncherScreen.Controllers -> ControllersScreen(
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
            is LauncherScreen.ControllerDetail -> {
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
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.EditButtons -> {
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
                                    if (finalized != null) controllersViewModel.refreshFromRouter()
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
            is LauncherScreen.LoggingSettings -> LoggingSettingsScreen(
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

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is DialogState.UpdateDownload
                || dialog is KeyboardInputState
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.Setup
                || currentScreen is LauncherScreen.Installing
                || currentScreen is LauncherScreen.Housekeeping
                || currentScreen is LauncherScreen.InputTester
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val hasContent = dev.cannoli.scorza.server.KitchenManager.isRunning
                || appSettings.showWifi
                || appSettings.showBluetooth
                || appSettings.showVpn
                || appSettings.showClock
                || appSettings.showBattery
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
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showVpn = appSettings.showVpn,
                showClock = appSettings.showClock,
                showBattery = appSettings.showBattery,
                showUpdate = appSettings.showUpdate,
                use24hTime = appSettings.use24h
            )
        }
        }
    }
    val settingsState = settingsViewModel.state.collectAsState().value
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == "display"
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == "portrait_margin"
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    if (osdMessage != null) {
        OsdPill(message = osdMessage)
    }
    }
    }
}
