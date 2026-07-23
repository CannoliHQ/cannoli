package dev.cannoli.scorza.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.PolaroidFrame
import dev.cannoli.scorza.R
import dev.cannoli.scorza.libretro.SaveSlotManager
import dev.cannoli.scorza.model.Rom
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SaveStatePickerScreen(
    rom: Rom,
    stateBasePath: String,
    slotOccupied: List<Boolean>,
    selectedSlotIndex: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    buttonStyle: ButtonStyle,
) {
    val slotManager = remember(stateBasePath) { SaveSlotManager(stateBasePath) }
    val thumbnail by produceState<Bitmap?>(null, stateBasePath, selectedSlotIndex) {
        value = withContext(Dispatchers.IO) {
            slotManager.slots.firstOrNull { it.index == selectedSlotIndex }
                ?.let { slotManager.loadThumbnail(it) }
        }
    }

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(Spacing.Lg)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScreenTitle(
                    text = rom.displayName,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center).width(320.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PolaroidFrame(
                    thumbnail = thumbnail,
                    selectedSlotIndex = selectedSlotIndex,
                    slotOccupied = slotOccupied,
                )
            }

            val occupied = slotOccupied.getOrElse(selectedSlotIndex) { false }
            val rightItems = if (occupied) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_load))
            } else {
                emptyList()
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = rightItems,
            )
        }
    }
}
