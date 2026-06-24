package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

data class ListSection<T>(val header: String?, val items: List<T>)

fun <T> List<ListSection<T>>.flattenItems(): List<T> = flatMap { it.items }

/**
 * Maps each section's first flat-item index to its header label, skipping empty and headerless
 * sections. Indices are into [flattenItems] (headers are not entries), so a consumer's selection
 * index lines up with the items without having to account for header rows.
 */
fun <T> List<ListSection<T>>.headerIndices(): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    var index = 0
    for (section in this) {
        if (section.items.isEmpty()) continue
        section.header?.let { result[index] = it }
        index += section.items.size
    }
    return result
}

@Composable
fun <T> SectionedList(
    sections: List<ListSection<T>>,
    selectedIndex: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    modifier: Modifier = Modifier,
    itemHeight: Dp = Dp.Unspecified,
    scrollTarget: Int = 0,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTarget.coerceAtLeast(0)),
    itemContent: @Composable (index: Int, item: T, isSelected: Boolean) -> Unit
) {
    val items = sections.flattenItems()
    val headers = sections.headerIndices()
    List(
        items = items,
        selectedIndex = selectedIndex,
        modifier = modifier,
        itemHeight = itemHeight,
        scrollTarget = scrollTarget,
        listState = listState
    ) { index, item, isSelected ->
        val header = headers[index]
        if (header != null) {
            Column {
                SectionHeader(
                    text = header,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    verticalPadding = verticalPadding
                )
                itemContent(index, item, isSelected)
            }
        } else {
            itemContent(index, item, isSelected)
        }
    }
}
