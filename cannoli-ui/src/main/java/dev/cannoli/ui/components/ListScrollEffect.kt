package dev.cannoli.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs

@Composable
fun ListScrollEffect(
    listState: LazyListState,
    selectedIndex: Int,
    itemCount: Int,
    scrollTarget: Int = -1,
    reorderMode: Boolean = false,
) {
    LaunchedEffect(itemCount, scrollTarget) {
        if (itemCount > 0 && scrollTarget >= 0) {
            val target = scrollTarget.coerceIn(0, itemCount - 1)
            if (listState.firstVisibleItemIndex != target) {
                listState.scrollToItem(target)
            }
        }
    }

    val prevIndex = remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex, itemCount) {
        val previous = prevIndex.intValue
        prevIndex.intValue = selectedIndex
        if (itemCount == 0) return@LaunchedEffect
        val index = selectedIndex.coerceAtLeast(0).coerceAtMost(itemCount - 1)
        // visibleItemsInfo can be empty mid-recomposition; under fast auto-repeat (80ms)
        // the empty window arrives often enough to leave the selected row off-screen.
        // Fall back to an unconditional scrollToItem so the selection never drifts out of view.
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            listState.scrollToItem(index)
            return@LaunchedEffect
        }
        val viewportHeight = listState.layoutInfo.viewportEndOffset
        val itemSize = visible.firstOrNull()?.size ?: 0
        // Rows that fit in the viewport, derived from the (uniform) row height so it holds even when
        // the list is currently scrolled and not every row is on screen. Counting only the rows that
        // happen to be fully visible right now under-counts after a scroll and wrongly concludes the
        // list can't fit, which then pushes the top row off to reveal the selection.
        val capacity = if (itemSize > 0) (viewportHeight / itemSize).coerceAtLeast(1) else visible.size
        if (itemCount <= capacity) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val fullyVisible = visible.filter { info -> info.offset >= 0 && info.offset + info.size <= viewportHeight }
        val fullyVisibleCount = fullyVisible.size.coerceAtLeast(1)
        val firstFullyVisible = fullyVisible.firstOrNull()?.index ?: 0
        val lastFullyVisible = fullyVisible.lastOrNull()?.index ?: 0

        if (index < firstFullyVisible) {
            // Single-step navigation reveals the row at the top of the viewport (minimal scroll).
            // When the selection teleports far upward (an item re-sorted to the top after a favorite
            // change) and lands within the first screenful, show the list from index 0 so the items
            // now above it stay visible instead of pinning it to the top of the viewport.
            val jumped = abs(index - previous) > 1
            val target = if (jumped && index < capacity) 0 else index
            listState.scrollToItem(target)
        } else if (index > lastFullyVisible) {
            val targetFirst = (index - fullyVisibleCount + 1).coerceAtLeast(0)
            listState.scrollToItem(targetFirst)
        }
    }
}
