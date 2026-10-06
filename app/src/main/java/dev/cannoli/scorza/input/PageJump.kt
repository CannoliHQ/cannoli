package dev.cannoli.scorza.input

import androidx.compose.foundation.lazy.LazyListState

object PageJump {

    fun compute(
        direction: Int,
        itemCount: Int,
        selectedIndex: Int,
        listState: LazyListState?,
        selectableRows: List<Int>? = null,
    ): Int {
        if (itemCount == 0) return selectedIndex
        val lastIndex = itemCount - 1
        val lastRendered = selectableRows?.lastOrNull() ?: lastIndex
        val rendered = readViewport(listState, lastRendered)
        val viewport = toSelectableSpace(rendered, selectableRows, lastIndex)
        val page = (viewport.last - viewport.first + 1).coerceAtLeast(1)

        return if (direction > 0) {
            when {
                selectedIndex < viewport.last -> viewport.last.coerceAtMost(lastIndex)
                selectedIndex >= lastIndex -> selectedIndex
                else -> (selectedIndex + page).coerceAtMost(lastIndex)
            }
        } else {
            when {
                selectedIndex > viewport.first -> viewport.first.coerceAtLeast(0)
                selectedIndex <= 0 -> selectedIndex
                else -> (selectedIndex - page).coerceAtLeast(0)
            }
        }
    }

    internal data class Viewport(val first: Int, val last: Int)

    internal fun toSelectableSpace(
        rendered: Viewport,
        selectableRows: List<Int>?,
        lastIndex: Int,
    ): Viewport {
        if (selectableRows == null) return rendered
        val first = selectableRows.indexOfFirst { it >= rendered.first }
        val last = selectableRows.indexOfLast { it <= rendered.last }
        if (first < 0 || last < 0 || last < first) {
            val nearest = selectableRows.indexOfFirst { it >= rendered.first }
            val fallback = (if (nearest >= 0) nearest else lastIndex).coerceIn(0, lastIndex)
            return Viewport(fallback, fallback)
        }
        return Viewport(first, last)
    }

    private fun readViewport(listState: LazyListState?, lastIndex: Int): Viewport {
        if (listState == null) return Viewport(0, lastIndex.coerceAtLeast(0))
        val info = listState.layoutInfo
        val viewportEnd = info.viewportEndOffset
        val fully = info.visibleItemsInfo.filter {
            it.offset >= 0 && it.offset + it.size <= viewportEnd
        }
        if (fully.isEmpty()) {
            val first = listState.firstVisibleItemIndex
            return Viewport(first, first)
        }
        return Viewport(fully.first().index, fully.last().index)
    }
}
