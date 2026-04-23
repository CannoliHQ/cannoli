package dev.cannoli.igm

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.PolaroidDark
import dev.cannoli.ui.theme.PolaroidInactive
import dev.cannoli.ui.theme.PolaroidSelect
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing


private val verticalPadding = 6.dp

@Composable
fun InGameMenu(
    gameTitle: String,
    menuOptions: InGameMenuOptions,
    selectedIndex: Int,
    selectedSlot: SaveSlotManager.Slot,
    slotThumbnail: Bitmap?,
    slotExists: Boolean,
    slotOccupied: kotlin.collections.List<Boolean>,
    undoLabel: String?,
    backLabel: String,
    deleteLabel: String,
    slotLabel: String,
    saveLabel: String,
    loadLabel: String,
    discLabel: String,
    selectLabel: String,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val showThumbnail = selectedIndex == menuOptions.saveStateIndex || selectedIndex == menuOptions.loadStateIndex
    val onDiscRow = selectedIndex == menuOptions.switchDiscIndex

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = gameTitle,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(if (showThumbnail) Modifier.weight(0.4f) else Modifier.weight(1f))
                    ) {
                        List(
                            items = menuOptions.options,
                            selectedIndex = selectedIndex,
                            itemHeight = itemHeight
                        ) { index, option, isSelected ->
                            if (index == menuOptions.switchDiscIndex) {
                                PillRowKeyValue(
                                    label = option,
                                    value = menuOptions.discLabel,
                                    isSelected = isSelected,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    verticalPadding = verticalPadding
                                )
                            } else {
                                PillRowText(
                                    label = option,
                                    isSelected = isSelected,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    verticalPadding = verticalPadding
                                )
                            }
                        }
                    }

                    if (showThumbnail) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(0.6f)
                                .padding(start = 16.dp),
                            contentAlignment = Alignment.Center
                        ) { Box(modifier = Modifier.widthIn(max = 280.dp)) {
                            PolaroidFrame(
                                thumbnail = slotThumbnail,
                                selectedSlotIndex = selectedSlot.index,
                                slotOccupied = slotOccupied
                            )
                        } }
                    }
                }
            }

            val canDeleteSlot = showThumbnail && slotExists
            val leftItems = buildList {
                add(buttonStyle.back to backLabel)
                if (undoLabel != null) add(buttonStyle.north to undoLabel.uppercase())
                if (canDeleteSlot) add(buttonStyle.west to deleteLabel)
            }
            val rightItems = when {
                selectedIndex == menuOptions.saveStateIndex -> listOf(DPAD_HORIZONTAL to slotLabel, buttonStyle.confirm to saveLabel)
                selectedIndex == menuOptions.loadStateIndex -> listOf(DPAD_HORIZONTAL to slotLabel, buttonStyle.confirm to loadLabel)
                onDiscRow -> listOf(DPAD_HORIZONTAL to discLabel, buttonStyle.confirm to selectLabel)
                else -> listOf(buttonStyle.confirm to selectLabel)
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        }
    }
}

@Composable
fun PolaroidFrame(
    thumbnail: Bitmap?,
    selectedSlotIndex: Int,
    slotOccupied: kotlin.collections.List<Boolean>,
    showIndicators: Boolean = true
) {
    val selectedColor = PolaroidSelect

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Sm))
            .background(Color.White)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (showIndicators) 12.dp else 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(10f / 9f)
                .clip(RoundedCornerShape(Radius.Sm))
                .background(PolaroidDark),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "Empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GrayText
                )
            }
        }

        if (showIndicators) {
        Spacer(modifier = Modifier.height(Spacing.Sm))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val autoSelected = selectedSlotIndex == 0
            val autoOccupied = slotOccupied.getOrElse(0) { false }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.Sm))
                    .then(if (autoSelected) Modifier.background(selectedColor) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "AUTO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = when {
                            autoSelected -> Color.White
                            autoOccupied -> Color.Black
                            else -> PolaroidInactive
                        }
                    )
                )
            }
            Spacer(modifier = Modifier.width(6.dp))

            for (i in 1..10) {
                val occupied = slotOccupied.getOrElse(i) { false }
                val selected = selectedSlotIndex == i
                val dotSize = if (selected) 10.dp else 8.dp

                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(dotSize)
                        .then(
                            if (occupied) {
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) selectedColor else Color.Black)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, Color.Black, CircleShape)
                            }
                        )
                )
            }
        }
        }
    }
}
