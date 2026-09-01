package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius

enum class OsdPosition {
    TopStart,
    TopCenter,
    TopEnd,
    CenterStart,
    Center,
    CenterEnd,
    BottomStart,
    BottomCenter,
    BottomCenterLow,
    BottomEnd,
}

/**
 * Whether a pill is carrying words or a glyph, which need different boxes.
 *
 * The bundled face is a CJK font, so its metrics are cut for ideographs: ascent 1.120 plus descent
 * 0.365 is a 1.485em line box where a Latin face is nearer 1.2. Around a sentence that reads as
 * ordinary leading; around one icon it is mostly empty space, and no amount of padding reaches it
 * because it is the line box rather than the padding. An icon therefore sets its own.
 *
 * A Material Design glyph is also drawn well inside its em box, 0.500em of ink against 0.854em for
 * a triangle, so it needs a larger size to carry the same weight. Size, line box and padding travel
 * together because picking them apart is how a caller lands on a combination nobody chose.
 */
enum class OsdPillStyle(
    val fontSize: TextUnit,
    /** The pill's content height, not a text line height: what the glyph is centred in. */
    val contentHeight: TextUnit,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
) {
    Text(14.sp, TextUnit.Unspecified, 16.dp, 6.dp),
    Icon(28.sp, 16.8.sp, 12.dp, 5.dp),
}

@Composable
fun BoxScope.OsdPill(
    message: String,
    position: OsdPosition = OsdPosition.TopCenter,
    style: OsdPillStyle = OsdPillStyle.Text,
) = OsdPill(position, style) { OsdPillText(message, style.fontSize) }

/**
 * The pill, holding whatever the caller lays out in it.
 *
 * A slot rather than a string because a pill can carry things of different sizes: an icon beside a
 * frame rate. As one string they share a text baseline, which leaves the smaller of the two sitting
 * low against the larger; as separate items each is centred on its own.
 */
@Composable
fun BoxScope.OsdPill(
    position: OsdPosition = OsdPosition.TopCenter,
    style: OsdPillStyle = OsdPillStyle.Text,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalCannoliColors.current
    // Stated rather than left to the text. A pill is a percentage-rounded shape, half of its
    // smaller side, so a height that comes out at the font's own 1.485em leading stops being a
    // pill and turns into a circle.
    val fixedHeight = if (style.contentHeight.isSpecified) {
        with(LocalDensity.current) { style.contentHeight.toDp() } + style.verticalPadding * 2
    } else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(position.alignment())
            .padding(position.edgePadding())
            .clip(Radius.Pill)
            .background(colors.highlight)
            .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
            .padding(
                horizontal = style.horizontalPadding,
                vertical = if (fixedHeight != null) 0.dp else style.verticalPadding,
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * One run of text in a pill, in the pill's ink.
 *
 * Measured at the font's own height rather than the pill's: constrained, the text keeps its top and
 * loses its bottom, which puts a glyph low. Free, it centres exactly, because the over-game glyphs
 * sit within half a font unit of the line box's centre and the surplus leading falls away evenly.
 */
@Composable
fun OsdPillText(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
        color = LocalCannoliColors.current.highlightText,
        modifier = Modifier.wrapContentHeight(unbounded = true),
    )
}

private fun OsdPosition.alignment(): Alignment = when (this) {
    OsdPosition.TopStart -> Alignment.TopStart
    OsdPosition.TopCenter -> Alignment.TopCenter
    OsdPosition.TopEnd -> Alignment.TopEnd
    OsdPosition.CenterStart -> Alignment.CenterStart
    OsdPosition.Center -> Alignment.Center
    OsdPosition.CenterEnd -> Alignment.CenterEnd
    OsdPosition.BottomStart -> Alignment.BottomStart
    OsdPosition.BottomCenter -> Alignment.BottomCenter
    OsdPosition.BottomCenterLow -> Alignment.BottomCenter
    OsdPosition.BottomEnd -> Alignment.BottomEnd
}

// Insets per position. Top/bottom-center get more breathing room (status bar / nav clearance);
// corner anchors hug closer to the edge so they don't read as floating in the middle.
private fun OsdPosition.edgePadding(): androidx.compose.foundation.layout.PaddingValues {
    val zero = 0.dp
    val center = 50.dp
    val centerLow = 12.dp
    val corner = 16.dp
    return when (this) {
        OsdPosition.TopCenter -> androidx.compose.foundation.layout.PaddingValues(top = center)
        OsdPosition.BottomCenter -> androidx.compose.foundation.layout.PaddingValues(bottom = center)
        OsdPosition.BottomCenterLow -> androidx.compose.foundation.layout.PaddingValues(bottom = centerLow)
        OsdPosition.TopStart -> androidx.compose.foundation.layout.PaddingValues(top = corner, start = corner)
        OsdPosition.TopEnd -> androidx.compose.foundation.layout.PaddingValues(top = corner, end = corner)
        OsdPosition.BottomStart -> androidx.compose.foundation.layout.PaddingValues(bottom = corner, start = corner)
        OsdPosition.BottomEnd -> androidx.compose.foundation.layout.PaddingValues(bottom = corner, end = corner)
        OsdPosition.CenterStart -> androidx.compose.foundation.layout.PaddingValues(start = corner)
        OsdPosition.CenterEnd -> androidx.compose.foundation.layout.PaddingValues(end = corner)
        OsdPosition.Center -> androidx.compose.foundation.layout.PaddingValues(zero)
    }
}

/**
 * A block of figures over the game, for the debug panel.
 *
 * The same ink and ground as the pills, so everything drawn over a game reads as one family, but
 * squared off rather than pill shaped: [Radius.Pill] is half the smaller side, which on a block
 * several lines tall would bow the sides into an oval.
 *
 * Labels are dimmed against their values so the eye lands on the figures, which are what change.
 */
@Composable
fun BoxScope.OsdPanel(
    rows: List<Pair<String, String>>,
    position: OsdPosition = OsdPosition.TopStart,
) {
    if (rows.isEmpty()) return
    val colors = LocalCannoliColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .align(position.alignment())
            .padding(position.edgePadding())
            .clip(RoundedCornerShape(Radius.Md))
            .background(colors.highlight)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = OsdPanelLabelSp),
                    color = colors.highlightText.copy(alpha = 0.55f),
                    modifier = Modifier.width(OsdPanelLabelWidth),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = OsdPanelValueSp),
                    color = colors.highlightText,
                )
            }
        }
    }
}

private val OsdPanelLabelSp = 13.sp
private val OsdPanelValueSp = 14.sp

// Fixed so the values line up in a column rather than stepping in and out as labels change width.
private val OsdPanelLabelWidth = 68.dp
