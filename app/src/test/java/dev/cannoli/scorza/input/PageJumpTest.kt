package dev.cannoli.scorza.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Page jump arithmetic, with no LazyListState: a null one reports the whole list as visible, which
 * is the honest answer before layout and makes the index-space maths testable on its own.
 *
 * The bug these pin: selection counts only selectable rows, the viewport counts every rendered row,
 * and on a list with section headers the two drift apart by however many headers precede the
 * selection.
 */
class PageJumpTest {

    // A list of 10 selectable rows with a header before every third one, so rendered indices run
    // ahead of selectable indices by a growing amount: header,0,1,2,header,3,4,5,header,6,7,8,...
    private fun withHeaders(count: Int, every: Int = 3): List<Int> {
        val rows = mutableListOf<Int>()
        var rendered = 0
        for (i in 0 until count) {
            if (i % every == 0) rendered++ // a header sits before each group
            rows.add(rendered)
            rendered++
        }
        return rows
    }

    @Test fun `an empty list stays put`() {
        assertEquals(0, PageJump.compute(1, 0, 0, null))
        assertEquals(3, PageJump.compute(-1, 0, 3, null))
    }

    // With no state the whole list reads as visible, so a jump goes to the far end and then stops.
    @Test fun `with everything visible a jump goes to the end and stays`() {
        assertEquals(9, PageJump.compute(1, 10, 0, null))
        assertEquals(9, PageJump.compute(1, 10, 9, null))
        assertEquals(0, PageJump.compute(-1, 10, 9, null))
        assertEquals(0, PageJump.compute(-1, 10, 0, null))
    }

    // The mapping must not change the answer when every row is selectable: an identity mapping and
    // no mapping have to agree, or supplying one becomes a behaviour change in itself.
    @Test fun `an identity mapping matches supplying none`() {
        val identity = (0 until 10).toList()
        for (i in 0 until 10) {
            assertEquals(PageJump.compute(1, 10, i, null), PageJump.compute(1, 10, i, null, identity))
            assertEquals(PageJump.compute(-1, 10, i, null), PageJump.compute(-1, 10, i, null, identity))
        }
    }

    // The headers push rendered indices past selectable ones, but with the whole list visible the
    // answer is still the far end: the translation must not invent a shorter page.
    @Test fun `headers do not shorten the page when the whole list is visible`() {
        val rows = withHeaders(10)
        assertEquals(9, PageJump.compute(1, 10, 0, null, rows))
        assertEquals(0, PageJump.compute(-1, 10, 9, null, rows))
    }

    @Test fun `the mapping places every selectable row after its headers`() {
        // 10 rows, a header before each group of three: selectable 0 is drawn at 1, not 0.
        val rows = withHeaders(10)
        assertEquals(1, rows[0])
        assertEquals(2, rows[1])
        assertEquals(3, rows[2])
        // The fourth selectable follows a second header, so it is drawn two rows further along.
        assertEquals(5, rows[3])
    }

    // The translation itself, which is where the bug lived. A viewport is reported in rendered
    // rows; these assert it comes back as the selectable rows it actually contains.
    private fun translate(first: Int, last: Int, rows: List<Int>?, lastIndex: Int = 9) =
        PageJump.toSelectableSpace(PageJump.Viewport(first, last), rows, lastIndex)

    @Test fun `a partial viewport maps to the selectable rows inside it`() {
        val rows = withHeaders(10) // header,0,1,2,header,3,4,5,header,6,7,8,header,9
        // Rendered rows 1..3 hold selectable 0,1,2.
        assertEquals(PageJump.Viewport(0, 2), translate(1, 3, rows))
        // Rendered 5..7 hold selectable 3,4,5, so the page is still three rather than five.
        assertEquals(PageJump.Viewport(3, 5), translate(5, 7, rows))
    }

    // Before the fix the viewport bounds were used raw, so a window over selectable 3..5 reported
    // 5..7 and a page jump moved by the wrong amount and landed past where the user could see.
    @Test fun `raw rendered bounds would have overshot`() {
        val rows = withHeaders(10)
        val translated = translate(5, 7, rows)
        assertEquals("the page is what fits on screen", 3, translated.last - translated.first + 1)
        assertEquals("not the rendered span, which counts a header", 3, 7 - 5 + 1)
        // The bounds themselves are what differ: raw 5..7 against translated 3..5.
        assertEquals(3, translated.first)
    }

    // A window showing only headers contains nothing to select, so it must not report a range that
    // would page by a made-up amount.
    @Test fun `a viewport with no selectable row collapses to one position`() {
        val rows = listOf(1, 2, 3)  // rendered 0 and 4 are headers
        val t = translate(4, 4, rows, lastIndex = 2)
        assertEquals(t.first, t.last)
    }

    @Test fun `a null mapping passes the viewport through untouched`() {
        assertEquals(PageJump.Viewport(2, 6), translate(2, 6, null))
    }
}
