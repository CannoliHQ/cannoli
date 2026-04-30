package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.ui.viewmodel.ConnectedRow
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Spacing

sealed interface ControllersListEntry {
    val isSelectable: Boolean
    data class Header(val label: String) : ControllersListEntry { override val isSelectable = false }
    data class Hint(val text: String) : ControllersListEntry { override val isSelectable = false }
    data class ConnectedItem(val row: ConnectedRow) : ControllersListEntry { override val isSelectable = true }
    data class SavedItem(val mapping: DeviceMapping) : ControllersListEntry { override val isSelectable = true }
}

@Composable
fun ControllersScreen(
    screen: LauncherScreen.Controllers,
    viewModel: ControllersViewModel,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    val connectedHeader = stringResource(R.string.controllers_connected_now).uppercase()
    val emptyHint = stringResource(R.string.controllers_empty_connected)
    val savedHeader = stringResource(R.string.controllers_saved_mappings).uppercase()

    val entries = remember(state, connectedHeader, emptyHint, savedHeader) {
        buildList {
            add(ControllersListEntry.Header(connectedHeader))
            if (state.connected.isEmpty()) {
                add(ControllersListEntry.Hint(emptyHint))
            } else {
                state.connected.forEach { add(ControllersListEntry.ConnectedItem(it)) }
            }
            if (state.savedMappings.isNotEmpty()) {
                add(ControllersListEntry.Header(savedHeader))
                state.savedMappings.forEach { add(ControllersListEntry.SavedItem(it)) }
            }
        }
    }

    val selectableIndices = remember(entries) {
        entries.mapIndexedNotNull { idx, e -> if (e.isSelectable) idx else null }
    }
    val highlightedEntryIndex = selectableIndices.getOrNull(
        screen.selectedIndex.coerceIn(0, (selectableIndices.size - 1).coerceAtLeast(0))
    ) ?: -1

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = stringResource(R.string.setting_controllers),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = entries,
                    selectedIndex = highlightedEntryIndex,
                    itemHeight = itemHeight,
                ) { index, entry, isSelected ->
                    when (entry) {
                        is ControllersListEntry.Header -> SectionHeader(
                            text = entry.label,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllersListEntry.Hint -> HintRow(
                            text = entry.text,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllersListEntry.ConnectedItem -> {
                            val portLabel = when {
                                entry.row.isBuiltIn -> "—"
                                entry.row.port != null -> "P${entry.row.port + 1}"
                                else -> "—"
                            }
                            PillRowKeyValue(
                                label = entry.row.mapping.displayName,
                                value = portLabel,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                        }
                        is ControllersListEntry.SavedItem -> PillRowText(
                            label = entry.mapping.displayName,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = if (highlightedEntryIndex >= 0)
                    listOf(buttonStyle.confirm to stringResource(R.string.label_select))
                else emptyList()
            )
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .height(pillItemHeight(lineHeight, verticalPadding))
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (fontSize.value * 0.72f).sp,
                lineHeight = lineHeight,
                color = colors.text.copy(alpha = 0.6f)
            )
        )
    }
}

@Composable
private fun HintRow(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .height(pillItemHeight(lineHeight, verticalPadding))
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = colors.text.copy(alpha = 0.6f)
            )
        )
    }
}
