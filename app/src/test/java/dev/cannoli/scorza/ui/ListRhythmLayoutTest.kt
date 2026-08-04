package dev.cannoli.scorza.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.cannoli.scorza.input.PageJump
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListRhythm
import dev.cannoli.ui.components.LocalListRhythm
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.bottomBarHeight
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillLineHeightSp
import dev.cannoli.ui.components.pillNominalGap
import dev.cannoli.ui.components.pillScaleFor
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.components.screenTitleMetrics
import dev.cannoli.ui.components.solveListRhythm
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalScaleFactor
import androidx.compose.foundation.layout.BoxWithConstraints
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lays out the real title / list / footer stack and measures what a reader sees. The footer is a
 * plain box of [bottomBarHeight] rather than a live BottomBar because Robolectric's stub font does
 * not measure the legend pills the way a device does; the bar's own height is verified separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-xhdpi")
class ListRhythmLayoutTest {

    @get:Rule val compose = createComposeRule()

    private var solved: ListRhythm? = null
    private var aboveInk = 0f
    private var belowInk = 0f
    private var titleBelowInk = 0f
    private var edgeDp = 0f
    private lateinit var listState: LazyListState

    @Composable
    private fun Screen(textSize: Int) {
        CannoliTheme {
            CompositionLocalProvider(
                LocalScaleFactor provides textSize / 22f,
                LocalPillScale provides pillScaleFor(textSize)
            ) {
                val fontSize = textSize.sp
                val lineHeight = pillLineHeightSp(textSize).sp
                val vp = pillVerticalPadding()
                val itemHeight = pillItemHeight(lineHeight, vp)
                BoxWithConstraints(modifier = Modifier.requiredSize(640.dp, 480.dp)) {
                    val insets = screenInsets()
                    val density = LocalDensity.current
                    fun Dp.snap(): Dp = with(density) { roundToPx().toDp() }
                    edgeDp = pillNominalGap().value / 2f
                    val metrics = screenTitleMetrics(fontSize, lineHeight)
                    aboveInk = metrics.rowAboveInk.value
                    belowInk = metrics.rowBelowInk.value
                    titleBelowInk = metrics.titleBelowInk.value
                    val rhythm = solveListRhythm(
                        available = (maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()).snap(),
                        titleHeight = metrics.height.snap(),
                        barHeight = bottomBarHeight().snap(),
                        rowHeight = itemHeight.snap(),
                        topExtra = metrics.rowBelowInk + pillNominalGap() / 2 - metrics.titleBelowInk,
                        bottomExtra = metrics.rowAboveInk + pillNominalGap() / 2,
                        pixel = with(density) { 1.toDp() },
                    )
                    solved = rhythm
                    CompositionLocalProvider(LocalListRhythm provides rhythm) {
                        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = footerReservation())
                            ) {
                                Box(modifier = Modifier.testTag("title")) {
                                    ScreenTitle(text = "SETTINGS", fontSize = fontSize, lineHeight = lineHeight)
                                }
                                Spacer(modifier = Modifier.height(listTitleSpacing()))
                                listState = rememberLazyListState()
                                List(
                                    items = (0 until 60).toList(),
                                    selectedIndex = 0,
                                    itemHeight = itemHeight,
                                    listState = listState,
                                    key = { _, item -> item }
                                ) { _, item, isSelected ->
                                    Box(modifier = Modifier.testTag("row$item")) {
                                        PillRowText(
                                            label = "Item $item",
                                            isSelected = isSelected,
                                            fontSize = fontSize,
                                            lineHeight = lineHeight,
                                            verticalPadding = vp
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(bottomBarHeight())
                                    .testTag("footer")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertEvenRhythm(textSize: Int) {
        compose.setContent { Screen(textSize) }
        compose.waitForIdle()

        val pillScale = minOf(1f, textSize / 24f)
        val lineHeight = textSize + 10f * pillScale
        val edge = edgeDp

        val title = compose.onNodeWithTag("title").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("footer").getUnclippedBoundsInRoot()
        val row0 = compose.onNodeWithTag("row0").getUnclippedBoundsInRoot()
        val row1 = compose.onNodeWithTag("row1").getUnclippedBoundsInRoot()

        var last = 0
        for (i in 0 until 60) {
            if (compose.onAllNodesWithTag("row$i").fetchSemanticsNodes().isEmpty()) break
            val b = compose.onNodeWithTag("row$i").getUnclippedBoundsInRoot()
            if (b.bottom.value > footer.top.value + 0.5f) break
            last = i
        }
        val lastRow = compose.onNodeWithTag("row$last").getUnclippedBoundsInRoot()

        // Ink to ink, which is what a reader compares.
        val titleGap = (row0.top.value + edge + aboveInk) - (title.bottom.value - titleBelowInk)
        val rowGap = (row1.top.value + edge + aboveInk) -
            (row0.top.value + edge + lineHeight - belowInk)
        val footerGap = footer.top.value - (lastRow.top.value + edge + lineHeight - belowInk)

        val rhythm = solved!!
        val onePixel = with(compose.density) { 1.toDp().value }
        val label = "textSize=$textSize (title=$titleGap row=$rowGap footer=$footerGap)"

        // The title spacer and the footer reservation come from one value, so those two agree to
        // within the pixel each was rounded down by. Row spacing is quantised too, and the leftover
        // lands in the other two, which bounds the drift at a pixel per row it was taken from.
        // Spacer, reservation and the balance shifted between them each round to a pixel of their own.
        assertEquals("$label title vs footer", titleGap, footerGap, onePixel * 3 + 0.05f)
        val slack = onePixel * (rhythm.rows + 1) / 2f * 1.5f + 0.05f
        assertEquals("$label title vs row", rowGap, titleGap, slack)
        assertEquals("$label footer vs row", rowGap, footerGap, slack)
    }

    /**
     * The rows the rhythm promises have to actually fit. One pixel over and the bottom row is
     * clipped, which drops it out of the fully-visible set PageJump reads, so the first page-down
     * stops on the second-to-last row on screen instead of the last.
     */
    private fun assertPageDownReachesTheBottomRow(textSize: Int) {
        compose.setContent { Screen(textSize) }
        compose.waitForIdle()

        val rows = solved!!.rows
        val info = listState.layoutInfo
        val fullyVisible = info.visibleItemsInfo.filter {
            it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset
        }
        assertEquals(
            "textSize=$textSize solved $rows rows but only ${fullyVisible.size} are fully visible",
            rows,
            fullyVisible.size
        )
        assertEquals(
            "textSize=$textSize page down landed short",
            rows - 1,
            PageJump.compute(direction = 1, itemCount = 60, selectedIndex = 0, listState = listState)
        )
    }

    @Test fun pageDownAt10() = assertPageDownReachesTheBottomRow(10)
    @Test fun pageDownAt16() = assertPageDownReachesTheBottomRow(16)
    @Test fun pageDownAt22() = assertPageDownReachesTheBottomRow(22)
    @Test fun pageDownAt24() = assertPageDownReachesTheBottomRow(24)
    @Test fun pageDownAt32() = assertPageDownReachesTheBottomRow(32)

    @Test fun evenAt10() = assertEvenRhythm(10)
    @Test fun evenAt16() = assertEvenRhythm(16)
    @Test fun evenAt22() = assertEvenRhythm(22)
    @Test fun evenAt24() = assertEvenRhythm(24)
    @Test fun evenAt32() = assertEvenRhythm(32)
}
