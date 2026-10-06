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
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            listState.scrollToItem(index)
            return@LaunchedEffect
        }
        val viewportHeight = listState.layoutInfo.viewportEndOffset
        val itemSize = visible.maxOfOrNull { it.size } ?: 0
        val spacing = visible.zipWithNext()
            .maxOfOrNull { (above, below) -> below.offset - (above.offset + above.size) }
            ?.coerceAtLeast(0) ?: 0
        val capacity = if (itemSize > 0) {
            ((viewportHeight + spacing) / (itemSize + spacing)).coerceAtLeast(1)
        } else visible.size
        if (itemCount <= capacity) {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val fullyVisible = visible.filter { info -> info.offset >= 0 && info.offset + info.size <= viewportHeight }
        val firstFullyVisible = fullyVisible.firstOrNull()?.index ?: 0
        val lastFullyVisible = fullyVisible.lastOrNull()?.index ?: 0

        if (index < firstFullyVisible) {
            val jumped = abs(index - previous) > 1
            val target = if (jumped && index < capacity) 0 else index
            listState.scrollToItem(target)
        } else if (index > lastFullyVisible) {
            listState.scrollToItem(index)
            val revealed = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val slack = listState.layoutInfo.viewportEndOffset - (revealed?.size ?: 0)
            if (slack > 0) listState.scrollBy(-slack.toFloat())
        }
    }
}
