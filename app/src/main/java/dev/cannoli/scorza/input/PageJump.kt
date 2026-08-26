package dev.cannoli.scorza.input

import androidx.compose.foundation.lazy.LazyListState

object PageJump {

    /**
     * Where a page of Left or Right lands.
     *
     * [selectedIndex] and [itemCount] count selectable rows, because that is the space selection
     * moves in. The viewport does not: LazyList reports indices over every row it renders, headers
     * and dividers included. On a list where those coincide the two spaces are the same and nothing
     * has to be said, which is why this went unnoticed. On one that renders section headers the
     * spaces drift apart by however many non-selectable rows precede the selection, and a page jump
     * lands short or overshoots by exactly that many.
     *
     * [selectableRows] closes the gap: the rendered row index of each selectable row, in order, so
     * `selectableRows[i]` is where selectable `i` is drawn. Null means every row is selectable and
     * the two spaces are identical.
     */
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

    /**
     * The visible rendered rows, expressed as the selectable rows they contain: the first selectable
     * at or after the top of the viewport, and the last at or before the bottom. A viewport showing
     * only headers contains no selectable row at all, so it collapses to a single position rather
     * than reporting a range that would page by the wrong amount.
     */
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
