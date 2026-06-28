package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.romm.sync.DEFAULT_SLOT
import dev.cannoli.scorza.romm.sync.SlotInfo
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.theme.Spacing

private const val GLYPH_L1 = "L1"

@Composable
fun SaveSlotsScreen(
    slots: List<SlotInfo>,
    selectedIndex: Int,
    pendingDelete: Boolean,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle,
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val selected = slots.getOrNull(selectedIndex)
    val isAutosave = selected?.slot == DEFAULT_SLOT

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(Spacing.Lg)) {
            ScreenTitle(
                text = stringResource(R.string.save_slots_title),
                fontSize = listFontSize,
                lineHeight = listLineHeight,
            )

            List(
                items = slots,
                selectedIndex = selectedIndex,
                itemHeight = itemHeight,
                modifier = Modifier.fillMaxSize().padding(top = itemHeight),
            ) { _, slot, isSelected ->
                PillRowKeyValue(
                    label = slot.slot,
                    value = if (slot.isActive) stringResource(R.string.save_slots_active) else "",
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                )
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = buildList {
                    add(buttonStyle.back to stringResource(R.string.label_back))
                    if (!isAutosave && selected != null) {
                        add(GLYPH_L1 to stringResource(R.string.save_slots_delete))
                        add(buttonStyle.west to stringResource(R.string.save_slots_rename))
                    }
                },
                rightItems = buildList {
                    if (selected != null) add(buttonStyle.confirm to stringResource(R.string.save_slots_switch))
                    add(buttonStyle.north to stringResource(R.string.save_slots_create))
                },
            )
        }

        if (pendingDelete && selected != null) {
            ConfirmOverlay(
                message = stringResource(R.string.save_slots_delete_confirm, selected.slot),
                buttonStyle = buttonStyle,
                confirmLabel = stringResource(R.string.save_slots_delete),
            )
        }
    }
}
