package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.romm.sync.DEFAULT_SLOT
import dev.cannoli.scorza.romm.sync.SlotInfo
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.pillItemHeight

private const val GLYPH_L1 = "L1"

@Composable
fun SaveSlotsScreen(
    title: String,
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

    Box(modifier = Modifier.fillMaxSize()) {
        ListDialogScreen(
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            title = title,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            leftBottomItems = buildList {
                if (!isAutosave && selected != null) {
                    add(GLYPH_L1 to stringResource(R.string.save_slots_delete))
                    add(buttonStyle.west to stringResource(R.string.save_slots_rename))
                }
            },
            rightBottomItems = buildList {
                if (selected != null) add(buttonStyle.confirm to stringResource(R.string.save_slots_switch))
                add(buttonStyle.north to stringResource(R.string.save_slots_create))
            },
            buttonStyle = buttonStyle,
        ) {
            List(
                items = slots,
                selectedIndex = selectedIndex,
                itemHeight = itemHeight,
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
