package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * The vertical spacing a list screen is laid out from, so that title to first row, row to row and
 * last row to footer all read as the same gap.
 *
 * Rows only come in whole units, so a screen almost never divides evenly into them. Rather than let
 * the division remainder pile up as dead space above the footer, it is spread back over every gap,
 * which is why [itemSpacing] is normally a little wider than a row's own padding would give.
 */
data class ListRhythm(
    val titleSpacer: Dp,
    val itemSpacing: Dp,
    val footerReserve: Dp,
    val rows: Int,
    val rowHeight: Dp,
)

private fun Dp.floorTo(unit: Dp): Dp =
    if (unit <= 0.dp) this else (floor(value / unit.value + 1e-4f) * unit.value).dp

val LocalListRhythm = staticCompositionLocalOf<ListRhythm?> { null }
val LocalUntitledListRhythm = staticCompositionLocalOf<ListRhythm?> { null }

/**
 * Wraps a screen that leaves its [ScreenTitle] out, so the title's height and the gap under it go
 * back to the rows instead of sitting unused above the footer. Screens whose title is conditional
 * (an empty launcher title, a settings sublist with no category label) need this on the branch that
 * draws no title, or they lose a row to space nothing occupies.
 */
@Composable
fun WithoutScreenTitle(active: Boolean = true, content: @Composable () -> Unit) {
    val untitled = LocalUntitledListRhythm.current
    if (!active || untitled == null) content()
    else CompositionLocalProvider(LocalListRhythm provides untitled, content = content)
}

/**
 * Solves the spacing that makes `title | rows | footer` fill [available] exactly, with every gap a
 * reader sees the same size.
 *
 * Those gaps run ink to ink, and the boxes around the ink are not symmetric, so each end needs its
 * own allowance on top of the shared row spacing `E`. [topExtra] covers the title's descent against
 * the row's leading, [bottomExtra] the row's leading against the footer's hard pill edge; a titled
 * screen then spans `topExtra + n*rowHeight + (n+1)*E + bottomExtra`, an untitled one drops the
 * title term and one slot with it. `n` is the largest row count leaving `E` at or above zero, so
 * rows never draw closer together than their boxes allow.
 */
fun solveListRhythm(
    available: Dp,
    titleHeight: Dp,
    barHeight: Dp,
    rowHeight: Dp,
    topExtra: Dp,
    bottomExtra: Dp,
    titled: Boolean = true,
    pixel: Dp = 0.dp,
): ListRhythm {
    // A spacer cannot be negative, so clamp here rather than at the end: clamping the result would
    // hand back more height than the solve budgeted and overrun the screen. A font whose title
    // descends deeper than a row's own below-ink plus its padding simply cannot be pulled in further.
    val top = if (titled) topExtra.coerceAtLeast(0.dp) else 0.dp
    val bottom = bottomExtra.coerceAtLeast(0.dp)
    val span = available - (if (titled) titleHeight else 0.dp) - barHeight
    val fixed = top + bottom
    if (rowHeight <= 0.dp || span <= fixed + rowHeight) {
        return ListRhythm(
            titleSpacer = top,
            itemSpacing = 0.dp,
            footerReserve = barHeight + bottom,
            rows = 1,
            rowHeight = rowHeight,
        )
    }
    val rows = ((span - fixed) / rowHeight).toInt().coerceAtLeast(1)
    val slots = if (titled) rows + 1 else rows
    // Row spacing has to land on a whole pixel: LazyColumn rounds its arrangement once and then
    // repeats it, so a fractional gap would be lost from every row and pile up above the footer.
    // The title spacer and the footer reservation are each placed once, so they take the leftover.
    //
    // Quantise DOWN, never to nearest. Compose rounds the spacer, the reservation and the row height
    // to pixels independently, so a solve carried out in fractions can claim a row that overruns the
    // screen by a pixel - and a pixel over means the bottom row is clipped, drops out of the
    // fully-visible test PageJump and reveal scrolling both read, and page-down stops one row short.
    // Rounding down can only leave height spare, so the row count the solve promises always fits.
    val ideal = (span - fixed - rowHeight * rows) / slots
    val spacing = ideal.floorTo(pixel).coerceAtLeast(0.dp)
    // Whatever the quantising left over is split between the two ends, which are each placed once.
    val slack = (span - fixed - rowHeight * rows - spacing * slots).coerceAtLeast(0.dp)
    val share = (if (titled) slack / 2 else slack).floorTo(pixel)
    return ListRhythm(
        titleSpacer = spacing + top + share,
        itemSpacing = spacing,
        footerReserve = barHeight + spacing + bottom + share,
        rows = rows,
        rowHeight = rowHeight,
    )
}
