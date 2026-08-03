package dev.cannoli.scorza.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.components.List
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SectionedList draws the section header inside the list item, so a row carrying a header is twice
 * as tall as a plain one. These tests pin the invariant that survives that: whatever the row
 * heights, moving the selection must leave the selected row fully inside the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListScrollEffectTest {

    @get:Rule val compose = createComposeRule()

    private val rowHeight = 20.dp
    private val viewportHeight = 100.dp

    private lateinit var listState: LazyListState
    private val selected = mutableIntStateOf(0)

    private fun render(count: Int, tallRows: Set<Int>) {
        selected.intValue = 0
        compose.setContent {
            listState = rememberLazyListState()
            Box(modifier = Modifier.height(viewportHeight)) {
                List(
                    items = (0 until count).toList(),
                    selectedIndex = selected.intValue,
                    itemHeight = rowHeight,
                    listState = listState,
                ) { index, _, _ ->
                    Box(
                        modifier = Modifier
                            .height(if (index in tallRows) rowHeight * 2 else rowHeight)
                            .fillMaxWidth()
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSelectionOnScreen(index: Int) {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        assertNotNull("row $index is not on screen at all", item)
        assertTrue(
            "row $index is clipped: offset=${item!!.offset} size=${item.size} viewport=${info.viewportEndOffset}",
            item.offset >= 0 && item.offset + item.size <= info.viewportEndOffset
        )
    }

    private fun walkDown(count: Int) {
        for (index in 0 until count) {
            selected.intValue = index
            compose.waitForIdle()
            assertSelectionOnScreen(index)
        }
    }

    private fun walkUp(count: Int) {
        for (index in count - 1 downTo 0) {
            selected.intValue = index
            compose.waitForIdle()
            assertSelectionOnScreen(index)
        }
    }

    @Test fun `uniform rows stay visible walking down`() {
        render(count = 20, tallRows = emptySet())
        walkDown(20)
    }

    @Test fun `uniform rows stay visible walking back up`() {
        render(count = 20, tallRows = emptySet())
        walkDown(20)
        walkUp(20)
    }

    /**
     * The Credits localization list: nine contributors, six of them opening a language section.
     * Counting rows back from the selection used to overshoot here and leave it clipped.
     */
    @Test fun `header rows stay visible walking down`() {
        render(count = 9, tallRows = setOf(0, 1, 3, 4, 5, 8))
        walkDown(9)
    }

    @Test fun `header rows stay visible walking back up`() {
        render(count = 9, tallRows = setOf(0, 1, 3, 4, 5, 8))
        walkDown(9)
        walkUp(9)
    }

    @Test fun `every row tall stays visible`() {
        render(count = 12, tallRows = (0 until 12).toSet())
        walkDown(12)
    }

    /**
     * A plain row sitting at the top of the viewport used to be sampled as the whole list's row
     * height, doubling the estimated capacity, so a list that does not fit was treated as one that
     * does and snapped back to the top with the selection left off-screen.
     */
    /**
     * A plain row sitting at the top of the viewport used to be sampled as the whole list's row
     * height, doubling the estimated capacity, so a list that does not fit was treated as one that
     * does and snapped back to the top with the selection left off-screen.
     *
     * Five rows totalling 180dp against a 100dp viewport: enough rows that the short row's height
     * makes `itemCount <= capacity` read true, and a layout where scrolling down does park that
     * short row at the top.
     */
    @Test fun `list that does not fit is not snapped to the top`() {
        render(count = 5, tallRows = setOf(0, 2, 3, 4))
        walkDown(5)
        assertTrue(
            "list was snapped back to the top even though its rows do not fit",
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        )
    }

    @Test fun `short list that fits is pinned to the top`() {
        render(count = 3, tallRows = emptySet())
        walkDown(3)
        assertTrue(
            "list fits on one screen so it should stay at index 0",
            listState.firstVisibleItemIndex == 0
        )
    }

    @Test fun `jumping straight to the last row keeps it visible`() {
        render(count = 30, tallRows = (0 until 30 step 2).toSet())
        selected.intValue = 29
        compose.waitForIdle()
        assertSelectionOnScreen(29)
    }

    @Test fun `jumping back to the first row keeps it visible`() {
        render(count = 30, tallRows = (0 until 30 step 2).toSet())
        selected.intValue = 29
        compose.waitForIdle()
        selected.intValue = 0
        compose.waitForIdle()
        assertSelectionOnScreen(0)
    }
}
