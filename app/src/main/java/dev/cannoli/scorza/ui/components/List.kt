package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun <T> List(
    items: List<T>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    itemHeight: Dp = Dp.Unspecified,
    scrollTarget: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    onVisibleRangeChanged: ((firstVisible: Int, visibleCount: Int) -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    ListScrollEffect(listState, selectedIndex, items.size, scrollTarget, onVisibleRangeChanged)

    if (itemHeight != Dp.Unspecified) {
        BoxWithConstraints(modifier = modifier) {
            val fullItems = (maxHeight / itemHeight).toInt()
            val constrainedHeight = itemHeight * fullItems
            LazyColumn(
                state = listState,
                modifier = Modifier.height(constrainedHeight),
                contentPadding = PaddingValues(bottom = 2000.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    itemContent(index, item)
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(bottom = 2000.dp)
        ) {
            itemsIndexed(items) { index, item ->
                itemContent(index, item)
            }
        }
    }
}
