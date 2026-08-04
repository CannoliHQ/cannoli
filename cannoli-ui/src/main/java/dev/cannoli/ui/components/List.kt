package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun <T> List(
    items: List<T>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    itemHeight: Dp = Dp.Unspecified,
    scrollTarget: Int = 0,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTarget.coerceAtLeast(0)),
    reorderMode: Boolean = false,
    onListStateChanged: ((LazyListState?) -> Unit)? = null,
    key: ((index: Int, item: T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T, isSelected: Boolean) -> Unit
) {
    ListScrollEffect(listState, selectedIndex, items.size, scrollTarget, reorderMode)

    if (onListStateChanged != null) {
        DisposableEffect(listState) {
            onListStateChanged(listState)
            onDispose { onListStateChanged(null) }
        }
    }

    val rhythm = LocalListRhythm.current
    val spacing = rhythm?.itemSpacing ?: 0.dp
    // The rhythm already divided the screen into rows; re-deriving the count from measured pixels
    // rounds each row independently and can land a row short, which is what costs a short handheld a
    // whole row. Only fall back to measuring when this list is not the one the rhythm was solved for.
    val solvedRows = rhythm?.takeIf { abs(it.rowHeight.value - itemHeight.value) < 0.5f }?.rows

    val listModifier = if (itemHeight != Dp.Unspecified) {
        modifier.layout { measurable, constraints ->
            val itemPx = itemHeight.roundToPx()
            val spacingPx = spacing.roundToPx()
            val measured = ((constraints.maxHeight + spacingPx) / (itemPx + spacingPx)).coerceAtLeast(1)
            // Never lay out more rows than fit: one row over and the last one is clipped, which
            // takes it out of the fully-visible set that page jumps and reveal scrolling read.
            val fullItems = solvedRows?.coerceAtMost(measured) ?: measured
            val heightPx = (fullItems * itemPx + (fullItems - 1) * spacingPx)
                .coerceAtMost(constraints.maxHeight)
            val placeable = measurable.measure(
                constraints.copy(maxHeight = heightPx, minHeight = 0)
            )
            layout(placeable.width, heightPx) {
                placeable.place(0, 0)
            }
        }
    } else {
        modifier
    }

    LazyColumn(
        state = listState,
        modifier = listModifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = PaddingValues(bottom = 2000.dp)
    ) {
        itemsIndexed(items, key = key) { index, item ->
            itemContent(index, item, selectedIndex == index)
        }
    }
}
