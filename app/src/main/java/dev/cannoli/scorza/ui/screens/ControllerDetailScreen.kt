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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRow
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.ErrorText
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Spacing

private sealed interface ControllerDetailEntry {
    data class KeyValue(val label: String, val value: String) : ControllerDetailEntry
    data class Editable(val label: String, val value: String) : ControllerDetailEntry
    data class Chevron(val label: String) : ControllerDetailEntry
    data class Destructive(val label: String) : ControllerDetailEntry
}

@Composable
fun ControllerDetailScreen(
    screen: LauncherScreen.ControllerDetail,
    mapping: DeviceMapping?,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            if (mapping == null) {
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
                    NotFoundHint(
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
                BottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                    rightItems = emptyList()
                )
                return@ScreenBackground
            }

            val entries = buildList {
                add(ControllerDetailEntry.Chevron(stringResource(R.string.controllers_edit_buttons)))
                add(
                    ControllerDetailEntry.KeyValue(
                        label = stringResource(R.string.controllers_confirm_button),
                        value = if (mapping.menuConfirm == CanonicalButton.BTN_EAST)
                            stringResource(R.string.value_east)
                        else
                            stringResource(R.string.value_south)
                    )
                )
                add(
                    ControllerDetailEntry.KeyValue(
                        label = stringResource(R.string.controllers_glyph_style),
                        value = mapping.glyphStyle.name
                    )
                )
                add(
                    ControllerDetailEntry.KeyValue(
                        label = stringResource(R.string.controllers_exclude_from_gameplay),
                        value = if (mapping.excludeFromGameplay)
                            stringResource(R.string.value_on)
                        else
                            stringResource(R.string.value_off)
                    )
                )
                add(
                    ControllerDetailEntry.Editable(
                        label = stringResource(R.string.controllers_name),
                        value = mapping.displayName
                    )
                )
                if (mapping.userEdited) {
                    add(ControllerDetailEntry.Destructive(stringResource(R.string.controllers_reset_defaults)))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = mapping.displayName,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = entries,
                    selectedIndex = screen.selectedIndex.coerceIn(0, entries.size - 1),
                    itemHeight = itemHeight,
                ) { _, entry, isSelected ->
                    when (entry) {
                        is ControllerDetailEntry.KeyValue -> PillRowKeyValue(
                            label = entry.label,
                            value = entry.value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllerDetailEntry.Editable -> PillRowKeyValue(
                            label = entry.label,
                            value = entry.value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllerDetailEntry.Chevron -> PillRowKeyValue(
                            label = entry.label,
                            value = "›",
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                        is ControllerDetailEntry.Destructive -> DestructiveRow(
                            label = entry.label,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }

            val coercedIndex = screen.selectedIndex.coerceIn(0, entries.size - 1)
            val selectedEntry = entries.getOrNull(coercedIndex)
            val isCycleRow = selectedEntry is ControllerDetailEntry.KeyValue
            val isEditableRow = selectedEntry is ControllerDetailEntry.Editable
            val confirmLabel = if (isEditableRow) R.string.label_rename else R.string.label_select
            val leftItems = buildList {
                add(buttonStyle.back to stringResource(R.string.label_back))
                if (isCycleRow) add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
            }
            val rightItems = listOf(buttonStyle.confirm to stringResource(confirmLabel))

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        }
    }
}

@Composable
private fun DestructiveRow(
    label: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val baseStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
    )
    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight) {
        Text(
            text = label,
            style = baseStyle,
            color = ErrorText,
        )
    }
}

@Composable
private fun NotFoundHint(
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
            text = stringResource(R.string.controllers_mapping_not_found),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = colors.text.copy(alpha = 0.6f),
            )
        )
    }
}
