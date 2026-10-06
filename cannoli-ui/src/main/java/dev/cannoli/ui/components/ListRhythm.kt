package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

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

@Composable
fun WithoutScreenTitle(active: Boolean = true, content: @Composable () -> Unit) {
    val untitled = LocalUntitledListRhythm.current
    if (!active || untitled == null) content()
    else CompositionLocalProvider(LocalListRhythm provides untitled, content = content)
}

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
    // Quantise DOWN, never to nearest. Compose rounds the spacer, the reservation and the row height
    // to pixels independently, so a solve carried out in fractions can claim a row that overruns the
    // screen by a pixel - and a pixel over means the bottom row is clipped, drops out of the
    // fully-visible test PageJump and reveal scrolling both read, and page-down stops one row short.
    // Rounding down can only leave height spare, so the row count the solve promises always fits.
    val ideal = (span - fixed - rowHeight * rows) / slots
    val spacing = ideal.floorTo(pixel).coerceAtLeast(0.dp)
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
