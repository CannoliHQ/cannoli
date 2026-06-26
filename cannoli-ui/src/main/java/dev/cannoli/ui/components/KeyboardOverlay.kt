package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.KEY_SHIFT
import dev.cannoli.ui.KEY_SPACE
import dev.cannoli.ui.KEY_SYMBOLS
import dev.cannoli.ui.R
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import kotlinx.coroutines.delay

private val KEY_BG = Color.White.copy(alpha = 0.12f)

@Composable
fun KeyboardOverlay(
    state: KeyboardState,
    title: String? = null,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val text = state.text
    val cursorPos = state.cursorPos
    val keyRow = state.keyRow
    val keyCol = state.keyCol
    val caps = state.caps
    val symbols = state.symbols
    val typo = LocalCannoliTypography.current
    val rows = getKeyboardRows(state.layout, caps, symbols)
    val row = keyRow.coerceIn(0, rows.lastIndex)
    val col = keyCol.coerceIn(0, rows[row].lastIndex)
    val colors = LocalCannoliColors.current
    val highlight = colors.highlight
    val highlightText = colors.highlightText

    val scrollState = rememberScrollState()
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorVisible = !cursorVisible
        }
    }

    LaunchedEffect(cursorPos, text) {
        cursorVisible = true
        val clamped = cursorPos.coerceIn(0, text.length)
        val target = if (text.isEmpty()) 0
        else (scrollState.maxValue * clamped / (text.length + 1).coerceAtLeast(1))
        scrollState.scrollTo(target.coerceAtLeast(0))
    }

    val safeCursor = cursorPos.coerceIn(0, text.length)
    val beforeCursor = text.substring(0, safeCursor)
    val afterCursor = text.substring(safeCursor)
    val cursorChar = if (cursorVisible) "|" else " "
    val displayText = "$beforeCursor$cursorChar$afterCursor"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = footerReservation()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = typo.titleMedium,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.Md))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.Md))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clipToBounds()
                    .horizontalScroll(scrollState)
            ) {
                Text(
                    text = displayText,
                    style = typo.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Md))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxKeysPerRow = rows.maxOf { it.size }
                val keyGap = 4.dp
                val keySize = ((maxWidth - keyGap * (maxKeysPerRow - 1)) / maxKeysPerRow).coerceAtMost(48.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(keyGap)
            ) {
            rows.forEachIndexed { ri, keys ->
                if (keys.size == 1 && keys[0] == KEY_SPACE) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val isSelected = ri == row
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(keySize)
                                .widthIn(max = 400.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.Md))
                                .background(if (isSelected) highlight else KEY_BG),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(2.dp)
                                    .background(if (isSelected) highlightText.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f))
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(keyGap, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        keys.forEachIndexed { ci, key ->
                            val isSelected = ri == row && ci == col
                            val isAction = key in listOf(KEY_SHIFT, KEY_ENTER, KEY_BACKSPACE, KEY_SYMBOLS)
                            val isShiftActive = key == KEY_SHIFT && caps

                            val keyBg = when {
                                isSelected -> highlight
                                isShiftActive -> highlight.copy(alpha = 0.5f)
                                else -> KEY_BG
                            }
                            val keyText = if (isSelected) highlightText else Color.White

                            Box(
                                modifier = Modifier
                                    .size(keySize)
                                    .clip(RoundedCornerShape(Radius.Md))
                                    .background(keyBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = if (isAction) 18.sp else 16.sp
                                    ),
                                    color = keyText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(
                buttonStyle.west to stringResource(R.string.label_cancel)
            ),
            rightItems = listOf(
                START_GLYPH to stringResource(R.string.label_confirm)
            )
        )
    }
}
