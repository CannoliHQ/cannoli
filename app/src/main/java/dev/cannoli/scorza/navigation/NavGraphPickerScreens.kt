package dev.cannoli.scorza.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliIcons

@Composable
internal fun PickerScreens(
    currentScreen: LauncherScreen,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)?,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    dialog: DialogState,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    cannoliColors: CannoliColors,
    listVerticalPadding: Dp,
    itemHeight: Dp,
) {
    when (currentScreen) {
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
                        // bodyLarge carries a fixed 22sp, so an empty state left on it ignored
                        // the text size the user chose and sat dimmer than every row around it.
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                        ),
                        color = cannoliColors.text,
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
                        // bodyLarge carries a fixed 22sp, so an empty state left on it ignored
                        // the text size the user chose and sat dimmer than every row around it.
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                        ),
                        color = cannoliColors.text,
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
        is LauncherScreen.IconGallery -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            ListDialogScreen(
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                title = stringResource(R.string.title_icon_gallery),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = emptyList(),
                buttonStyle = labels
            ) {
                val sections = remember {
                    CannoliIcons.all.groupBy { it.category }
                        .map { (header, icons) -> ListSection(header = header, items = icons) }
                }
                SectionedList(
                    sections = sections,
                    selectedIndex = currentScreen.selectedIndex,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    scrollTarget = currentScreen.scrollTarget,
                    onListStateChanged = onListStateChanged
                ) { _, icon, isSelected ->
                    // The glyph sits immediately left of the name it claims to be, so a
                    // codepoint that resolves to the wrong glyph is visible at a glance.
                    PillRowKeyValue(
                        label = "${icon.constantName}: ${icon.purpose}",
                        value = icon.glyphName,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        valueIcon = icon.glyph,
                    )
                }
            }
        }
        is LauncherScreen.DirectoryBrowser -> {
            inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.directoryBrowserHandler) }
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
        else -> {}
    }
}
