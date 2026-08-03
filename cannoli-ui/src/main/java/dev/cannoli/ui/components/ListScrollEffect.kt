package dev.cannoli.ui.components

import androidx.compose.foundation.gestures.scrollBy
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
        // The tallest row on screen, not the first one: SectionedList draws the header inside the
        // item, so sampling whichever row happens to be on top reads a plain row as the whole list's
        // height and doubles the capacity. Taking the max keeps the estimate conservative, so the
        // fits-on-one-screen branch below can never fire for a list that does not actually fit.
        val itemSize = visible.maxOfOrNull { it.size } ?: 0
        // Rows that fit in the viewport, derived from the row height so it holds even when the list
        // is currently scrolled and not every row is on screen. Counting only the rows that happen to
        // be fully visible right now under-counts after a scroll and wrongly concludes the list can't
        // fit, which then pushes the top row off to reveal the selection.
        val capacity = if (itemSize > 0) (viewportHeight / itemSize).coerceAtLeast(1) else visible.size
        if (itemCount <= capacity) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val fullyVisible = visible.filter { info -> info.offset >= 0 && info.offset + info.size <= viewportHeight }
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
            // Reveal the row at the bottom edge. Counting rows back from the selection would assume
            // every row is the same height, which SectionedList breaks by drawing the header inside
            // the item; the count taken from the old window then overshoots and leaves the selection
            // clipped. Scroll the row to the top so it gets measured, then give back the slack.
            listState.scrollToItem(index)
            val revealed = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val slack = listState.layoutInfo.viewportEndOffset - (revealed?.size ?: 0)
            if (slack > 0) listState.scrollBy(-slack.toFloat())
        }
    }
}
