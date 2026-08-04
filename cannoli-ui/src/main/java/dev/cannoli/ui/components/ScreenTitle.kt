package dev.cannoli.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors


val LocalStatusBarLeftEdge = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(Int.MAX_VALUE) }

const val TitleFontScale = 1.3f

/**
 * What a [ScreenTitle] occupies, measured rather than assumed.
 *
 * [height] is what it lays out at: the title draws larger than the row font it shares a line height
 * with, so this has to come from the same measurement Compose will make.
 *
 * [overhang] is the dead space it carries below its baseline over and above what a row carries -
 * descent plus leading, scaled up by the larger title font. Nothing is drawn there, so a gap
 * measured from the box bottom reads that much wider to the eye than the same gap under a row. The
 * footer has no equivalent: a legend pill's top edge is solid. Subtracting it from the spacer under
 * the title is what makes the top and bottom gaps look the same.
 */
/**
 * Where the ink sits inside the boxes a list screen is built from.
 *
 * The gaps a reader judges run ink to ink, but layout places boxes, and a row's box is not
 * symmetric about its text: the space above the glyphs (ascent and leading) exceeds the space
 * below. Equal boxes therefore do not read as equal gaps. These let the solve convert between the
 * two - [rowAboveInk] and [rowBelowInk] are measured from the row's own baseline, and
 * [titleBelowInk] from the title's, which sits lower because the title draws larger.
 */
data class ScreenTitleMetrics(
    val height: Dp,
    val titleBelowInk: Dp,
    val rowAboveInk: Dp,
    val rowBelowInk: Dp,
)

/**
 * Height of a capital above the baseline, for the font actually in use.
 *
 * Compose reports baselines but not ink extents, and Cannoli ships two fonts the user switches
 * between, so a fixed ratio would be wrong for one of them. Resolving the family to a [Typeface] and
 * asking for real glyph bounds costs one measurement and is correct for whichever font is loaded.
 */
@Composable
private fun capHeightPx(style: TextStyle, fontSizePx: Float): Float {
    val resolver = LocalFontFamilyResolver.current
    return remember(style.fontFamily, style.fontWeight, style.fontStyle, fontSizePx) {
        val typeface = resolver.resolve(
            fontFamily = style.fontFamily,
            fontWeight = style.fontWeight ?: FontWeight.Normal,
            fontStyle = style.fontStyle ?: FontStyle.Normal,
        ).value as? Typeface
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = fontSizePx
        }
        val bounds = Rect()
        paint.getTextBounds("H", 0, 1, bounds)
        // getTextBounds is relative to the baseline origin, so the ink top is negative.
        (-bounds.top).toFloat()
    }
}

@Composable
fun screenTitleMetrics(fontSize: TextUnit, lineHeight: TextUnit): ScreenTitleMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.bodyLarge
    val titleStyle = base.copy(
        fontSize = (fontSize.value * TitleFontScale).sp,
        lineHeight = lineHeight
    )
    val rowStyle = base.copy(fontSize = fontSize, lineHeight = lineHeight)
    val capHeight = capHeightPx(rowStyle, with(density) { fontSize.toPx() })
    return remember(titleStyle, rowStyle, density, capHeight) {
        val title = measurer.measure("Ag", titleStyle, constraints = Constraints())
        val row = measurer.measure("Ag", rowStyle, constraints = Constraints())
        with(density) {
            ScreenTitleMetrics(
                height = title.size.height.toDp(),
                titleBelowInk = (title.size.height - title.firstBaseline).toDp(),
                rowAboveInk = (row.firstBaseline - capHeight).coerceAtLeast(0f).toDp(),
                rowBelowInk = (row.size.height - row.firstBaseline).coerceAtLeast(0f).toDp(),
            )
        }
    }
}

@Composable
fun ScreenTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    trailing: @Composable (() -> Unit)? = null
) {
    if (trailing != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TitleText(
                text = text,
                fontSize = fontSize,
                lineHeight = lineHeight,
                modifier = Modifier.weight(1f)
            )
            trailing()
        }
    } else {
        TitleText(
            text = text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TitleText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val statusBarLeftPx = LocalStatusBarLeftEdge.current.intValue
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    MarqueeEffect(scrollState, active = true, key = text)

    val scaledFontSizeSp = fontSize.value * TitleFontScale
    val internalPadding = pillInternalPadding()

    BoxWithConstraints(modifier = modifier) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { (screenPadding + internalPadding).toPx() }
        val gapPx = with(density) { 16.dp.toPx() }
        val availableWidthPx = if (statusBarLeftPx < Int.MAX_VALUE) {
            (statusBarLeftPx - paddingPx - gapPx).coerceAtLeast(0f)
        } else {
            containerWidthPx
        }

        val resolvedFontSize = remember(text, scaledFontSizeSp, availableWidthPx) {
            val baseStyle = TextStyle(fontSize = scaledFontSizeSp.sp, lineHeight = lineHeight)
            val measured = textMeasurer.measure(text, baseStyle, constraints = Constraints())
            if (measured.size.width > availableWidthPx && scaledFontSizeSp > fontSize.value) {
                val scale = availableWidthPx / measured.size.width
                (scaledFontSizeSp * scale - 0.2f).coerceAtLeast(fontSize.value)
            } else {
                scaledFontSizeSp
            }
        }

        val availableWidthDp = with(density) { availableWidthPx.toDp() }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = resolvedFontSize.sp,
                lineHeight = lineHeight
            ),
            color = LocalCannoliColors.current.title,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(start = internalPadding)
                .then(
                    if (statusBarLeftPx < Int.MAX_VALUE) Modifier.widthIn(max = availableWidthDp)
                    else Modifier
                )
                .horizontalScroll(scrollState)
        )
    }
}
