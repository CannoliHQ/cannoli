package dev.cannoli.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import kotlinx.coroutines.delay

val screenPadding = 20.dp
val pillInternalH = 14.dp

// Short handhelds (the 3.5" AYANEO Pocket Micro is ~320dp tall) spend over a third of the
// screen on chrome, which costs a whole list row. Halve the vertical edge padding there.
private const val CompactScreenHeightDp = 400

@Composable
fun screenInsets(): PaddingValues {
    val compact = LocalConfiguration.current.screenHeightDp < CompactScreenHeightDp
    return PaddingValues(
        horizontal = screenPadding,
        vertical = if (compact) screenPadding / 2 else screenPadding
    )
}

// Hosts that solve a rhythm reserve exactly what the bar measures plus one gap. The in-game menu
// does not solve one, so it keeps the reservation it has always had.
@Composable
fun footerReservation(): Dp =
    LocalListRhythm.current?.footerReserve ?: (48 * LocalScaleFactor.current).dp

/** The spacer between a [ScreenTitle] and the list below it. */
@Composable
fun listTitleSpacing(): Dp = LocalListRhythm.current?.titleSpacer ?: 8.dp

/** Gap between two adjacent row labels when nothing is spread into it. */
@Composable
fun pillNominalGap(): Dp = (pillVerticalPadding() + pillInset()) * 2

// Pill geometry is a pure function of the text size so every host derives the same rows.
// It scales down from the reference size and never up, keeping small text at the reference
// proportions instead of drowning it in fixed padding.
const val PillReferenceTextSp = 24f
private const val PillLineHeightExtraSp = 10f
private const val PillVerticalPaddingDp = 6f
// Rows sit flush, so the row pitch is the pill itself and the highlight carries the separation.
// MinUI lays its list out the same way: `SCALE1(PADDING + (j * PILL_SIZE))`, pills contiguous.
private const val PillInsetDp = 0f

fun pillScaleFor(textSizeSp: Int): Float = minOf(1f, textSizeSp / PillReferenceTextSp)

fun pillLineHeightSp(textSizeSp: Int): Float =
    textSizeSp + PillLineHeightExtraSp * pillScaleFor(textSizeSp)

@Composable
fun pillVerticalPadding(): Dp = (PillVerticalPaddingDp * LocalPillScale.current).dp

@Composable
private fun pillInset(): Dp = (PillInsetDp * LocalPillScale.current).dp

@Composable
fun pillInternalPadding(): Dp = pillInternalH * LocalPillScale.current

@Composable
fun pillItemHeight(lineHeight: TextUnit, verticalPadding: Dp): Dp {
    return with(LocalDensity.current) { lineHeight.toDp() } + verticalPadding * 2 + pillInset() * 2
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
                .padding(vertical = pillInset())
                .clip(Radius.Pill)
                .background(colors.highlight)
                .padding(horizontal = pillInternalPadding(), vertical = verticalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier
                .then(heightMod)
                .padding(vertical = pillInset())
                .padding(horizontal = pillInternalPadding(), vertical = verticalPadding),
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
    checkState: Boolean? = null,
    tagSuffix: String? = null,
    labelColor: Color? = null
) {
    val colors = LocalCannoliColors.current
    val baseStyle = MaterialTheme.typography.bodyLarge
    val textStyle = remember(baseStyle, fontSize, lineHeight) {
        baseStyle.copy(fontSize = fontSize, lineHeight = lineHeight)
    }
    val textColor = labelColor ?: if (isSelected) colors.highlightText else colors.text

    val scrollState = rememberScrollState()
    MarqueeEffect(scrollState, isSelected, key = label to isSelected)

    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight) {
        BoxWithConstraints {
            val viewportMax = this.maxWidth
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checkState != null) {
                    PillCheckGlyph(checked = checkState, style = textStyle, color = textColor)
                }
                if (showReorderIcon) {
                    Text(
                        text = "\u2195",
                        style = textStyle,
                        color = textColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Row(
                    modifier = Modifier
                        .widthIn(max = viewportMax)
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = textStyle,
                        color = textColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (tagSuffix != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tagSuffix,
                            style = textStyle.copy(fontSize = (fontSize.value * 0.75f).sp),
                            color = if (isSelected) colors.highlightText else colors.accent,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
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
    swatchColor: Color? = null,
    valueIcon: String? = null,
    dotIndicator: Boolean? = null,
    checkState: Boolean? = null,
    leadingIcon: String? = null
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
    val valueColor = if (isSelected) colors.highlightText else colors.accent
    val borderColor = if (isSelected) colors.highlightText.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.3f)

    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (checkState != null) {
                PillCheckGlyph(checked = checkState, style = textStyle, color = labelColor)
            }
            if (leadingIcon != null) {
                // Styled off the label the way valueIcon is styled off the value, so the glyph
                // resolves the theme's line metrics rather than the icon font's own.
                Text(
                    text = leadingIcon,
                    style = textStyle.copy(fontFamily = LocalCannoliIconFont.current),
                    color = labelColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
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
            if (valueIcon != null) {
                // Same style as the value beside it, differing only in family. Passing fontSize
                // alone left lineHeight unspecified, so the glyph resolved the icon font's own
                // metrics while the value used the theme's, and CenterVertically then centred two
                // boxes of different heights.
                Text(
                    text = valueIcon,
                    style = valueStyle.copy(fontFamily = LocalCannoliIconFont.current),
                    color = labelColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = value,
                style = valueStyle,
                color = valueColor,
                maxLines = 1
            )
            if (dotIndicator != null) {
                val dotColor = if (dotIndicator) labelColor else labelColor.copy(alpha = 0.45f)
                if (value.isNotEmpty()) Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size((fontSize.value * 0.42f).dp)
                        .border(if (dotIndicator) 0.dp else 1.dp, dotColor, CircleShape)
                        .background(if (dotIndicator) dotColor else Color.Transparent, CircleShape)
                )
            }
        }
    }
}

@Composable
fun PillRowInfo(
    label: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    trailing: @Composable () -> Unit
) {
    val colors = LocalCannoliColors.current
    val baseStyle = MaterialTheme.typography.bodyLarge
    val textStyle = remember(baseStyle, fontSize, lineHeight) {
        baseStyle.copy(fontSize = fontSize, lineHeight = lineHeight)
    }
    val textColor = if (isSelected) colors.highlightText else colors.text

    val scrollState = rememberScrollState()
    MarqueeEffect(scrollState, isSelected, key = label to isSelected)

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
                    color = textColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/** The shared multi-select checkbox glyph used by every list row. Call inside a [Row]. */
@Composable
fun PillCheckGlyph(checked: Boolean, style: TextStyle, color: Color) {
    Text(
        text = if (checked) "☑" else "☐",
        style = style,
        color = color
    )
    Spacer(modifier = Modifier.width(8.dp))
}
