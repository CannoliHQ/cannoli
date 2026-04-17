package dev.cannoli.scorza.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.scorza.libretro.ControlsScreen
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.ui.components.CreditsOverlay
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.InstallingScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SetupScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.screens.isFullScreen
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.MessageOverlay
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
    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    data object Settings : LauncherScreen()
    data object InputTester : LauncherScreen()
    data class CoreMapping(val mappings: List<CoreMappingEntry>, val allMappings: List<CoreMappingEntry> = mappings, val selectedIndex: Int = 0, val scrollTarget: Int = 0, val filter: Int = 0) : LauncherScreen()
    data class CorePicker(val tag: String, val platformName: String, val cores: List<CorePickerOption>, val selectedIndex: Int = 0, val gamePath: String? = null, val scrollTarget: Int = 0, val activeIndex: Int = 0) : LauncherScreen()
    data class ColorList(val colors: List<ColorEntry>, val selectedIndex: Int = 0, val scrollTarget: Int = 0) : LauncherScreen()
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collections: List<String>, val displayNames: List<String> = emptyList(), val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), val scrollTarget: Int = 0) : LauncherScreen()
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), val scrollTarget: Int = 0) : LauncherScreen()
    data class ChildPicker(val collectionName: String, val collections: List<String>, val displayNames: List<String> = emptyList(), val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), val scrollTarget: Int = 0) : LauncherScreen()
    data class ProfileList(val profiles: List<String> = emptyList(), val selectedIndex: Int = 0, val scrollTarget: Int = 0) : LauncherScreen()
    data class ControlBinding(val selectedIndex: Int = 0, val scrollTarget: Int = 0, val controls: Map<String, Int> = emptyMap(), val listeningIndex: Int = -1, val listenCountdownMs: Int = 0, val profileName: String = ProfileManager.DEFAULT_GAME) : LauncherScreen()
    data class ShortcutBinding(val selectedIndex: Int = 0, val scrollTarget: Int = 0, val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(), val listening: Boolean = false, val heldKeys: Set<Int> = emptySet(), val countdownMs: Int = 0) : LauncherScreen()
    data class Credits(val selectedIndex: Int = 0, val scrollTarget: Int = 0) : LauncherScreen()
    data class InstalledCores(val cores: List<String> = emptyList(), val loading: Boolean = true, val selectedIndex: Int = 0, val scrollTarget: Int = 0, val title: String? = null) : LauncherScreen()
    data class DirectoryBrowser(
        val purpose: BrowsePurpose,
        val currentPath: String,
        val entries: List<String> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0
    ) : LauncherScreen()
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
}

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel? = null,
    gameListViewModel: GameListViewModel? = null,
    inputTesterViewModel: InputTesterViewModel,
    onExitInputTester: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    dialogState: StateFlow<DialogState>,
    onVisibleRangeChanged: (firstVisible: Int, visibleCount: Int, isViewportFull: Boolean) -> Unit = { _, _, _ -> },
    resumableGames: Set<String> = emptySet(),
    updateAvailable: Boolean = false,
    downloadProgress: Float = 0f,
    downloadError: String? = null
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = (appSettings.textSize.sp + 10).sp
    val listVerticalPadding = 6.dp

    val labels = dev.cannoli.ui.ButtonStyle(appSettings.buttonLabelSet, appSettings.confirmButton)

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

    CompositionLocalProvider(LocalCannoliColors provides cannoliColors, LocalStatusBarLeftEdge provides statusBarLeftEdge, LocalScaleFactor provides scaleFactor, LocalCannoliTypography provides cannoliTypography) {
    Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
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
                val canSelect = selected != null && selected.coreDisplayName != "Missing" && selected.coreDisplayName != "None"
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
                        val value = if (entry.runnerLabel.isNotEmpty())
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
                            swatchColor = androidx.compose.ui.graphics.Color(entry.color.toInt())
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
            is LauncherScreen.ProfileList -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_profiles),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = listOf(labels.north to stringResource(R.string.label_new), labels.confirm to stringResource(R.string.label_edit)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.profiles,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onVisibleRangeChanged = onVisibleRangeChanged
                    ) { _, name, isSelected ->
                        PillRowText(
                            label = name,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
                if (dialog is DialogState.DeleteProfileConfirm) {
                    ConfirmOverlay(message = stringResource(R.string.dialog_delete_profile, (dialog as DialogState.DeleteProfileConfirm).profileName))
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
            is LauncherScreen.ControlBinding -> {
                val tempInput = remember(currentScreen.controls) {
                    LibretroInput().also { input ->
                        for ((key, keyCode) in currentScreen.controls) {
                            val btn = input.buttons.find { it.prefKey == key } ?: continue
                            input.assign(btn, keyCode)
                        }
                    }
                }
                val selectedBtn = tempInput.buttons.getOrNull(currentScreen.selectedIndex)
                val canUnmap = selectedBtn != null
                        && selectedBtn.prefKey != "btn_menu"
                        && tempInput.getKeyCodeFor(selectedBtn) != LibretroInput.UNMAPPED
                        && currentScreen.listeningIndex < 0
                ControlsScreen(
                    input = tempInput,
                    selectedIndex = currentScreen.selectedIndex,
                    listeningIndex = currentScreen.listeningIndex,
                    listenCountdownMs = currentScreen.listenCountdownMs,
                    titleRes = R.string.title_button_mapping,
                    canUnmapSelected = canUnmap
                )
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
                            label = action.label,
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
                            val actionName = ShortcutAction.entries.getOrNull(currentScreen.selectedIndex)?.label ?: ""
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
        }

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val statusBarVisible = dialog !is DialogState.About && dialog !is DialogState.Kitchen && dialog !is DialogState.UpdateDownload && currentScreen !is LauncherScreen.Credits && currentScreen !is LauncherScreen.DirectoryBrowser && currentScreen !is LauncherScreen.Setup && currentScreen !is LauncherScreen.Installing && !(currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true) && (dev.cannoli.scorza.server.KitchenManager.isRunning || appSettings.showWifi || appSettings.showBluetooth || appSettings.showVpn || appSettings.showClock || appSettings.showBattery || (updateAvailable && appSettings.showUpdate))
        if (statusBarVisible && currentScreen !is LauncherScreen.InputTester) {
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
                showKitchen = dev.cannoli.scorza.server.KitchenManager.isRunning,
                use24hTime = appSettings.use24h,
                textSizeSp = appSettings.textSize.sp
            )
        }
        }
    }
    }
}
