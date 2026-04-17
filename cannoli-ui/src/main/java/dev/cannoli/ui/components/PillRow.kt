package dev.cannoli.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius

val screenPadding = 20.dp
val pillInternalH = 14.dp

@Composable
fun footerReservation(): Dp = (48 * LocalScaleFactor.current).dp

@Composable
fun pillItemHeight(lineHeight: TextUnit, verticalPadding: Dp): Dp {
    return with(LocalDensity.current) { lineHeight.toDp() } + verticalPadding * 2 + 4.dp
}

const val MarqueeInitialDelayMs = 800L

@Composable
fun MarqueeEffect(scrollState: ScrollState, active: Boolean, key: Any = active, initialDelayMs: Long = MarqueeInitialDelayMs) {
    LaunchedEffect(key) {
        scrollState.scrollTo(0)
        if (!active) return@LaunchedEffect
        delay(initialDelayMs)
        while (true) {
            val max = scrollState.maxValue
            if (max <= 0) break
            val duration = (max * 4).coerceIn(500, 8000)
            scrollState.animateScrollTo(max, animationSpec = tween(durationMillis = duration, easing = LinearEasing))
            delay(800)
            scrollState.animateScrollTo(0, animationSpec = tween(durationMillis = duration, easing = LinearEasing))
            delay(800)
        }
    }
}
@Composable
fun PillRow(
    isSelected: Boolean,
    verticalPadding: Dp = 8.dp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalCannoliColors.current
    val heightMod = if (lineHeight != TextUnit.Unspecified) {
        Modifier.height(pillItemHeight(lineHeight, verticalPadding))
    } else Modifier
    if (isSelected) {
        Box(
            modifier = modifier
                .then(heightMod)
                .padding(vertical = 2.dp)
                .clip(Radius.Pill)
                .background(colors.highlight)
                .padding(horizontal = pillInternalH, vertical = verticalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier
                .then(heightMod)
                .padding(vertical = 2.dp)
                .padding(horizontal = pillInternalH, vertical = verticalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            content()
        }
    }
}

@Composable
fun PillRowText(
    label: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    showReorderIcon: Boolean = false,
    checkState: Boolean? = null
) {
    val colors = LocalCannoliColors.current
    val baseStyle = MaterialTheme.typography.bodyLarge
    val textStyle = remember(baseStyle, fontSize, lineHeight) {
        baseStyle.copy(fontSize = fontSize, lineHeight = lineHeight)
    }

    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checkState != null) {
                Text(
                    text = if (checkState) "\u2611" else "\u2610",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (showReorderIcon) {
                Text(
                    text = "\u2195",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = textStyle,
                color = if (isSelected) colors.highlightText else colors.text
            )
        }
    }
}

@Composable
fun PillRowKeyValue(
    label: String,
    value: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    swatchColor: Color? = null
) {
    val colors = LocalCannoliColors.current
    val baseStyle = MaterialTheme.typography.bodyLarge
    val baseValueStyle = MaterialTheme.typography.bodyMedium
    val textStyle = remember(baseStyle, fontSize, lineHeight) {
        baseStyle.copy(fontSize = fontSize, lineHeight = lineHeight)
    }
    val valueStyle = remember(baseValueStyle, fontSize) {
        baseValueStyle.copy(fontSize = (fontSize.value * 0.72f).sp)
    }

    val scrollState = rememberScrollState()
    MarqueeEffect(scrollState, isSelected)

    val labelColor = if (isSelected) colors.highlightText else colors.text
    val valueColor = if (isSelected) colors.highlightText.copy(alpha = 0.5f) else colors.text.copy(alpha = 0.6f)
    val borderColor = if (isSelected) colors.highlightText.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.3f)

    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = textStyle,
                    color = labelColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (swatchColor != null) {
                Box(
                    modifier = Modifier
                        .size((fontSize.value * 0.7f).dp)
                        .clip(RoundedCornerShape(Radius.Sm))
                        .background(swatchColor)
                        .border(1.dp, borderColor, RoundedCornerShape(Radius.Sm))
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = valueStyle,
                color = valueColor,
                maxLines = 1
            )
        }
    }
}
