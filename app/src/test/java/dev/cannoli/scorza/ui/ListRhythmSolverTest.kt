package dev.cannoli.scorza.ui

import androidx.compose.ui.unit.dp
import dev.cannoli.ui.components.ListRhythm
import dev.cannoli.ui.components.solveListRhythm
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rhythm divides a screen into whole rows and hands the remainder back to the gaps. These pin
 * both halves of that: the content adds back up to no more than the screen it was given, and the
 * three gaps a reader actually sees - title to first row, row to row, last row to footer - come out
 * the same size once the ink offsets each end carries are taken into account.
 */
class ListRhythmSolverTest {

    private class Geo(
        val lineHeight: Float,
        val rowHeight: Float,
        val edge: Float,
        val aboveInk: Float,
        val belowInk: Float,
        val titleBelowInk: Float,
    ) {
        val topExtra get() = belowInk + edge - titleBelowInk
        val bottomExtra get() = aboveInk + edge
    }

    private fun geometry(textSize: Int, inkProfile: Int): Geo {
        val pillScale = minOf(1f, textSize / 24f)
        val lineHeight = textSize + 10f * pillScale
        val edge = 6f * pillScale
        // Three plausible shapes of font metric, including one where the title sits lower than the
        // row does, which drives topExtra negative.
        val (above, below, titleBelow) = when (inkProfile) {
            0 -> Triple(0.15f, 0.20f, 0.10f)
            1 -> Triple(0.25f, 0.10f, 0.30f)
            else -> Triple(0.05f, 0.05f, 0.05f)
        }
        return Geo(
            lineHeight = lineHeight,
            rowHeight = lineHeight + 2 * edge,
            edge = edge,
            aboveInk = above * textSize,
            belowInk = below * textSize,
            titleBelowInk = titleBelow * textSize,
        )
    }

    @Test
    fun contentNeverOverflowsTheScreen() {
        forEachCase { label, available, titled, pixel, g, r ->
            val titleUsed = if (titled) g.lineHeight + r.titleSpacer.value else 0f
            val used = titleUsed +
                r.rows * g.rowHeight +
                (r.rows - 1) * r.itemSpacing.value +
                r.footerReserve.value
            // Overflowing by even a pixel clips the bottom row out of the fully-visible set that
            // PageJump and reveal scrolling read, so the solve always rounds down.
            // 0.25dp is under half a pixel at any density this ships on, so it cannot cost a row:
            // List clamps its row count to what measurably fits regardless. It is float noise in
            // the Dp arithmetic, not real overflow.
            assertTrue("$label overflows: used $used > $available", used <= available + 0.25f)
        }
    }

    @Test
    fun theThreeGapsMatch() {
        forEachCase { label, _, titled, pixel, g, r ->
            val barHeight = 34f
            val rowGap = g.belowInk + g.edge + r.itemSpacing.value + g.edge + g.aboveInk
            val footerGap = g.belowInk + g.edge + (r.footerReserve.value - barHeight)
            // Row spacing is one whole-pixel value repeated across every slot, so quantising it down
            // strands up to a pixel per slot. That remainder goes to the ends, which a titled screen
            // has two of and an untitled screen only one.
            val slots = if (titled) r.rows + 1 else r.rows
            val ends = if (titled) 2f else 1f
            val slack = if (pixel == 0f) 0.05f else pixel * slots / ends + 0.05f

            assertTrue(
                "$label footer $footerGap vs row $rowGap",
                kotlin.math.abs(footerGap - rowGap) <= slack
            )
            if (titled) {
                val expected = if (g.topExtra < 0f) rowGap - g.topExtra else rowGap
                val titleGap = g.titleBelowInk + r.titleSpacer.value + g.edge + g.aboveInk
                assertTrue(
                    "$label title $titleGap vs expected $expected",
                    kotlin.math.abs(titleGap - expected) <= slack
                )
            }
        }
    }

    private fun forEachCase(
        check: (String, Int, Boolean, Float, Geo, ListRhythm) -> Unit
    ) {
        val barHeight = 34f
        for (textSize in 10..32) {
            for (available in 200..700 step 11) {
                for (titled in listOf(true, false)) {
                    for (pixel in listOf(0f, 0.5f, 1f)) {
                        for (inkProfile in 0..2) {
                            val g = geometry(textSize, inkProfile)
                            val span =
                                available - (if (titled) g.lineHeight else 0f) - barHeight
                            val fixed = (if (titled) g.topExtra else 0f) + g.bottomExtra
                            if (span <= fixed + g.rowHeight) continue
                            val label =
                                "ts=$textSize avail=$available titled=$titled px=$pixel ink=$inkProfile"
                            check(
                                label, available, titled, pixel, g,
                                solveListRhythm(
                                    available = available.dp,
                                    titleHeight = g.lineHeight.dp,
                                    barHeight = barHeight.dp,
                                    rowHeight = g.rowHeight.dp,
                                    topExtra = g.topExtra.dp,
                                    bottomExtra = g.bottomExtra.dp,
                                    titled = titled,
                                    pixel = pixel.dp,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
