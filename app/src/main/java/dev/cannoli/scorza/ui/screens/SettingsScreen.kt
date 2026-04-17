package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.theme.Spacing
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    dialogState: DialogState = DialogState.None,
    downloadProgress: Float = 0f,
    downloadError: String? = null,
    updateAvailable: Boolean = false,
    onVisibleRangeChanged: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding)
    ) {
        if (state.inSubList) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                val categoryLabel = state.activeCategoryLabel
                if (categoryLabel != null) {
                    ScreenTitle(
                        text = stringResource(categoryLabel),
                        fontSize = listFontSize,
                        lineHeight = listLineHeight
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                }
                List(
                    items = state.items,
                    selectedIndex = state.selectedIndex,
                    itemHeight = itemHeight,
                    onVisibleRangeChanged = onVisibleRangeChanged,
                    key = { _, item -> item.key }
                ) { _, item, isSelected ->
                    val hasValue = item.valueText != null || item.valueRes != null || item.swatchColor != null
                    if (hasValue) {
                        PillRowKeyValue(
                            label = item.labelText ?: stringResource(item.labelRes),
                            value = item.valueText ?: item.valueRes?.let { stringResource(it) } ?: "",
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = item.swatchColor
                        )
                    } else {
                        PillRowText(
                            label = item.labelText ?: stringResource(item.labelRes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }

            val selectedItem = state.items.getOrNull(state.selectedIndex)
            val isColorItem = selectedItem?.key?.startsWith("color_") == true
            val isEditableItem = selectedItem?.isEditable == true
            val showChange = !isEditableItem && selectedItem?.canCycle != false
            val leftItems = if (showChange) {
                listOf(buttonStyle.back to stringResource(R.string.label_back), DPAD_HORIZONTAL to stringResource(R.string.label_change))
            } else {
                listOf(buttonStyle.back to stringResource(R.string.label_back))
            }
            val showClear = selectedItem?.key == "rom_directory" && selectedItem.valueText != null
            val rightItems = if (isColorItem) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            } else if (showClear) {
                listOf(buttonStyle.north to stringResource(R.string.label_clear))
            } else {
                emptyList()
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = stringResource(R.string.settings_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = state.categories,
                    selectedIndex = state.categoryIndex,
                    itemHeight = itemHeight,
                    onVisibleRangeChanged = onVisibleRangeChanged,
                    key = { _, category -> category.key }
                ) { _, category, isSelected ->
                    PillRowText(
                        label = stringResource(category.labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            )
        }
    }
    }

    if (dialogState.isFullScreen) {
        DialogOverlay(
            dialogState = dialogState,
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            updateAvailable = updateAvailable,
            buttonStyle = buttonStyle
        )
    }
}
