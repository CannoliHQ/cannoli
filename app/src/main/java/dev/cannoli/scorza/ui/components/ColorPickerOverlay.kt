package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.ui.theme.Spacing
import dev.cannoli.igm.ui.theme.Radius
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.igm.KEY_BACKSPACE
import dev.cannoli.igm.KEY_ENTER
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.igm.ui.components.ScreenTitle
import dev.cannoli.igm.ui.components.screenPadding
import dev.cannoli.igm.ui.theme.COLOR_PRESETS
import dev.cannoli.igm.ui.theme.LocalCannoliColors

const val COLOR_GRID_COLS = 4

private fun colorDisplayName(argb: Long): String {
    val preset = COLOR_PRESETS.firstOrNull { it.color == argb }
    if (preset != null) return preset.name
    val rgb = argb and 0xFFFFFF
    return "#%06X".format(rgb)
}

@Composable
fun ColorPickerOverlay(
    title: String,
    selectedRow: Int,
    selectedCol: Int,
    currentColor: Long,
    titleFontSize: TextUnit = 22.sp,
    titleLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val currentName = colorDisplayName(currentColor)
    val highlight = LocalCannoliColors.current.highlight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp)
        ) {
            ScreenTitle(
                text = title,
                fontSize = titleFontSize,
                lineHeight = titleLineHeight
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(Radius.Lg))
                                .background(Color(currentColor))
                                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(Radius.Lg))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = currentName,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val rows = COLOR_PRESETS.chunked(COLOR_GRID_COLS)
                    rows.forEachIndexed { ri, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { ci, preset ->
                                val isSelected = ri == selectedRow && ci == selectedCol
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(Radius.Lg))
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, highlight, RoundedCornerShape(Radius.Lg))
                                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(Radius.Lg))
                                        )
                                        .background(Color(preset.color))
                                )
                            }
                        }
                        if (ri < rows.lastIndex) {
                            Spacer(modifier = Modifier.height(Spacing.Sm))
                        }
                    }
                }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = listOf(
                buttonStyle.north to stringResource(R.string.label_hex),
                buttonStyle.confirm to stringResource(R.string.label_select)
            )
        )
    }
}

val HEX_KEYS = listOf("0", "1", "2", "3", "4", "5", "6", "7", KEY_BACKSPACE,
                       "8", "9", "A", "B", "C", "D", "E", "F", KEY_ENTER)
const val HEX_ROW_SIZE = 9

@Composable
fun HexColorInputOverlay(
    title: String,
    currentHex: String,
    selectedIndex: Int,
    titleFontSize: TextUnit = 22.sp,
    titleLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val displayHex = "#$currentHex"
    val previewColor = if (currentHex.length == 6) {
        try { Color(0xFF000000 or currentHex.toLong(16)) } catch (_: Exception) { Color.Black }
    } else Color.Black
    val highlight = LocalCannoliColors.current.highlight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp)
        ) {
            ScreenTitle(
                text = title,
                fontSize = titleFontSize,
                lineHeight = titleLineHeight
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(Radius.Lg))
                                .background(previewColor)
                                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(Radius.Lg))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = displayHex,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    for (rowStart in 0 until HEX_KEYS.size step HEX_ROW_SIZE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in rowStart until (rowStart + HEX_ROW_SIZE).coerceAtMost(HEX_KEYS.size)) {
                                val key = HEX_KEYS[i]
                                val isSelected = i == selectedIndex
                                val isAction = key == KEY_BACKSPACE || key == KEY_ENTER
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(Radius.Md))
                                        .background(
                                            if (isSelected) highlight
                                            else Color.White.copy(alpha = 0.12f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = if (isAction) 18.sp else 16.sp
                                        ),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(Spacing.Xs))
                    }
                }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = emptyList()
        )
    }
}
