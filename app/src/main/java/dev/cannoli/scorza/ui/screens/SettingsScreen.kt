package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import dev.cannoli.scorza.ui.viewmodel.SettingsKey
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.SHOULDERS
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.WithoutScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenInsets

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenInsets())
    ) {
        if (state.inSubList) {
            WithoutScreenTitle(active = state.activeCategoryLabel == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                val categoryLabel = state.activeCategoryLabel
                if (categoryLabel != null) {
                    ScreenTitle(
                        text = stringResource(categoryLabel),
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                    )
                    Spacer(modifier = Modifier.height(listTitleSpacing()))
                }
                List(
                    items = state.items,
                    selectedIndex = state.selectedIndex,
                    itemHeight = itemHeight,
                    onListStateChanged = onListStateChanged,
                    key = { _, item -> item.key }
                ) { _, item, isSelected ->
                    val hasValue = item.valueText != null || item.valueRes != null || item.swatchColor != null
                    Box(modifier = Modifier.fillMaxWidth().alpha(if (item.disabled) 0.4f else 1f)) {
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
            }
            }

            val selectedItem = state.items.getOrNull(state.selectedIndex)
            val selectedKey = SettingsKey.fromId(selectedItem?.key)
            val isColorItem = selectedKey in SettingsKey.COLOR_ROWS
            val isEditableItem = selectedItem?.isEditable == true
            val isFghCollection = selectedKey == SettingsKey.FGH_COLLECTION
            val showChange = selectedItem?.canCycle != false && selectedItem?.disabled != true && (!isEditableItem || isFghCollection)
            // Rows measured in pixels or percent take a coarse step on the shoulders, so the D-pad
            // can stay on single units. Advertised only on those rows; elsewhere the shoulders do
            // nothing and a legend entry would be a lie.
            val takesCoarseStep = selectedKey == SettingsKey.PORTRAIT_MARGIN ||
                selectedKey in SettingsKey.SCREEN_GEOMETRY_ROWS
            val leftItems = if (showChange) {
                buildList {
                    add(buttonStyle.back to stringResource(R.string.label_back))
                    add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
                    if (takesCoarseStep) add(SHOULDERS to stringResource(R.string.label_jump))
                }
            } else {
                listOf(buttonStyle.back to stringResource(R.string.label_back))
            }
            val showClear = selectedKey == SettingsKey.ROM_DIRECTORY && selectedItem?.valueText != null
            // Rows that act rather than cycle or navigate. The two RomM pairing rows were the
            // only ones when this was a key check; the flag is what the check was really asking.
            val isRommPairAction = selectedItem?.isAction == true ||
                selectedKey == SettingsKey.ROMM_PAIR || selectedKey == SettingsKey.ROMM_PAIR_CODE
            val isNavInto = selectedItem?.isEditable == true
                && selectedItem.valueText == null
                && selectedItem.valueRes == null
                && selectedItem.swatchColor == null
                && !isFghCollection
            val rightItems = if (isColorItem) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            } else if (isFghCollection) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_choose))
            } else if (selectedKey == SettingsKey.START_ON_PLATFORM) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_change))
            } else if (state.activeCategory == SettingsCategory.SCREEN_GEOMETRY) {
                listOf(buttonStyle.north to stringResource(R.string.label_reset))
            } else if (showClear) {
                listOf(buttonStyle.north to stringResource(R.string.label_clear))
            } else if (isRommPairAction) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            } else if (isNavInto) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_open))
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
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = stringResource(R.string.settings_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                List(
                    items = state.categories,
                    selectedIndex = state.categoryIndex,
                    itemHeight = itemHeight,
                    onListStateChanged = onListStateChanged,
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
}
