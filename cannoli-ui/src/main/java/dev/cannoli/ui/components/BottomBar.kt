package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius

private const val GlyphTextSp = 14f
private const val GlyphPadVDp = 4f
private const val LegendTextSp = 12f
private const val LegendPadVDp = 6f

@Composable
private fun barTextStyle(sizeSp: Float, sf: Float, font: FontFamily = LocalCannoliFont.current): TextStyle = TextStyle(
    fontFamily = font,
    fontWeight = FontWeight.Bold,
    fontSize = (sizeSp * sf).sp,
    lineHeight = (sizeSp * sf).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    ),
)

@Composable
private fun barGlyphStyle(sf: Float): TextStyle = barTextStyle(GlyphTextSp, sf, LocalCannoliIconFont.current)

/**
 * What the bar actually measures, so the space reserved under a list matches it.
 *
 * The text is measured rather than taken as its line height: both runs set [LineHeightStyle.Trim]
 * to `Both`, which trims the line box back to the glyphs, so a run renders shorter than the line
 * height asks for. Assuming otherwise over-reserved by about 5dp.
 */
@Composable
fun bottomBarHeight(): Dp {
    val sf = LocalScaleFactor.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val glyphStyle = barGlyphStyle(sf)
    val legendStyle = barTextStyle(LegendTextSp, sf)
    return remember(glyphStyle, legendStyle, density, sf) {
        val glyphText = measurer.measure("A", glyphStyle, constraints = Constraints()).size.height
        val legendText = measurer.measure("A", legendStyle, constraints = Constraints()).size.height
        with(density) {
            val glyphPill = glyphText.toDp() + (GlyphPadVDp * 2 * sf).dp
            maxOf(glyphPill, legendText.toDp()) + (LegendPadVDp * 2 * sf).dp
        }
    }
}

@Composable
fun GlyphPill(content: @Composable () -> Unit) {
    val innerPill = LocalCannoliColors.current.accent.copy(alpha = 0.30f)
    val sf = LocalScaleFactor.current
    Box(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(innerPill)
            .padding(horizontal = (10 * sf).dp, vertical = (GlyphPadVDp * sf).dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun GlyphPill(button: String) {
    val accent = LocalCannoliColors.current.accent
    val sf = LocalScaleFactor.current
    GlyphPill {
        Text(
            text = button,
            style = barGlyphStyle(sf).copy(color = accent)
        )
    }
}

@Composable
fun LegendPill(label: String, glyphs: @Composable () -> Unit) {
    val accent = LocalCannoliColors.current.accent
    val outerPill = accent.copy(alpha = 0.15f)
    val sf = LocalScaleFactor.current

    Row(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(outerPill)
            .padding(start = (5 * sf).dp, end = (14 * sf).dp, top = (LegendPadVDp * sf).dp, bottom = (LegendPadVDp * sf).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)
    ) {
        glyphs()

        // One line, always: a legend that wraps both breaks mid-word on a narrow screen and renders
        // taller than bottomBarHeight measured, which is what the list reserves under itself.
        Text(
            text = label,
            style = barTextStyle(LegendTextSp, sf).copy(color = accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LegendPill(button: String, label: String) = LegendPill(label = label) { GlyphPill(button) }

@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    leftItems: List<Pair<String, String>>,
    rightItems: List<Pair<String, String>>
) {
    val sf = LocalScaleFactor.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            leftItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            rightItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }
    }
}
