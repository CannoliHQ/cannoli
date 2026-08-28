package dev.cannoli.scorza.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.SectionHeader
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliIcons

@Composable
internal fun EmulatorScreens(
    currentScreen: LauncherScreen,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)?,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    cannoliColors: CannoliColors,
    listVerticalPadding: Dp,
    itemHeight: Dp,
) {
    when (currentScreen) {
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
                leftBottomItems = listOf(
                    labels.west to if (currentScreen.alphabetical)
                        stringResource(R.string.label_group_alphabetical)
                    else
                        stringResource(R.string.label_group_manufacturer),
                    labels.north to filterLabel,
                ),
                rightBottomItems = buildList {
                    if (canSelect) add(labels.confirm to stringResource(R.string.label_select))
                },
                buttonStyle = labels
            ) {
                // Headers are rows, so the highlight is translated from the selectable-only
                // index the screen state carries, the same way PlatformMapping does it. Nothing
                // in the input path has to know grouping exists.
                val mappingRows = remember(currentScreen.mappings, currentScreen.alphabetical) {
                    if (currentScreen.alphabetical)
                        currentScreen.mappings.map { dev.cannoli.scorza.ui.screens.MappingListRow.Platform(it) }
                    else
                        dev.cannoli.scorza.ui.screens.groupMappingRows(currentScreen.mappings)
                }
                val mappingHighlight = remember(mappingRows, currentScreen.selectedIndex) {
                    mappingRows.withIndex()
                        .filter { it.value.isSelectable }
                        .getOrNull(currentScreen.selectedIndex)?.index ?: -1
                }
                List(
                    items = mappingRows,
                    selectedIndex = mappingHighlight,
                    itemHeight = itemHeight,
                    scrollTarget = currentScreen.scrollTarget,
                    onListStateChanged = onListStateChanged
                ) { _, row, isSelected ->
                    if (row is dev.cannoli.scorza.ui.screens.MappingListRow.Group) {
                        SectionHeader(
                            text = row.label,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        return@List
                    }
                    val entry = (row as dev.cannoli.scorza.ui.screens.MappingListRow.Platform).entry
                    val value = when {
                        entry.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NEEDS_SETUP -> stringResource(R.string.value_unmapped)
                        entry.runnerLabel.isEmpty() -> entry.coreDisplayName
                        else -> "${entry.coreDisplayName} (${entry.runnerLabel})"
                    }
                    val valueIcon = when (entry.status) {
                        dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED -> CannoliIcons.NotInstalled.glyph
                        else -> null
                    }
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
            val highlighted = currentScreen.items.getOrNull(highlightedIndex)
            val confirmLabel = if ((highlighted as? dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption)?.downloadable == true)
                stringResource(R.string.label_download)
            else
                stringResource(R.string.label_select)
            ListDialogScreen(
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                title = if (currentScreen.romId != null)
                    stringResource(R.string.title_game_mapping, currentScreen.gameName.orEmpty())
                else
                    stringResource(R.string.title_platform_mapping, currentScreen.platformName),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = buildList {
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
                        is dev.cannoli.scorza.ui.screens.MappingItem.PlatformDefault -> PillRowKeyValue(
                            label = item.label,
                            value = if (item.isCurrent) stringResource(R.string.value_active) else "",
                            isSelected = isSelected,
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
                                opt.availability == dev.cannoli.scorza.ui.screens.CoreAvailability.UNAVAILABLE -> {
                                    val resId = when (opt.runnerLabel) {
                                        "Internal" -> R.string.value_not_downloaded
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
                                valueIcon = if (item.isCurrent &&
                                    opt.availability == dev.cannoli.scorza.ui.screens.CoreAvailability.UNAVAILABLE
                                ) CannoliIcons.NotInstalled.glyph else null
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
                                    valueIcon = CannoliIcons.NotInstalled.glyph
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
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                            ),
                            color = cannoliColors.text,
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
                                            text = CannoliIcons.NotInstalled.glyph,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = dev.cannoli.ui.theme.LocalCannoliIconFont.current,
                                                fontSize = listFontSize,
                                            ),
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
                        items = currentScreen.overrides,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, item, isSelected ->
                        PillRowKeyValue(
                            label = item.gameName,
                            value = item.label,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }
        is LauncherScreen.InstalledCores -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val highlighted = currentScreen.rows.getOrNull(currentScreen.selectedIndex)
            ListDialogScreen(
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                title = stringResource(
                    R.string.title_installed_cores,
                    android.text.format.Formatter.formatShortFileSize(ctx, currentScreen.totalBytes),
                ),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = buildList {
                    // Only offered on a row it can act on: a core something still names has no
                    // uninstall, so the legend does not advertise an action that would refuse.
                    if (highlighted != null && !highlighted.inUse) {
                        add(labels.confirm to stringResource(R.string.label_uninstall))
                    }
                    if (currentScreen.reclaimableBytes > 0L) {
                        add(labels.north to stringResource(R.string.label_remove_unused))
                    }
                },
                buttonStyle = labels
            ) {
                if (currentScreen.rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.value_no_installed_cores),
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
                        items = currentScreen.rows,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged,
                        modifier = Modifier.fillMaxSize()
                    ) { _, row, isSelected ->
                        val size = android.text.format.Formatter.formatShortFileSize(ctx, row.sizeBytes)
                        PillRowKeyValue(
                            label = row.displayName,
                            value = stringResource(
                                if (row.inUse) R.string.value_core_used else R.string.value_core_unused,
                                size,
                            ),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }
        else -> {}
    }
}
