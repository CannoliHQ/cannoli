package dev.cannoli.scorza.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PageJumpTest {

    private fun withHeaders(count: Int, every: Int = 3): List<Int> {
        val rows = mutableListOf<Int>()
        var rendered = 0
        for (i in 0 until count) {
            if (i % every == 0) rendered++
            rows.add(rendered)
            rendered++
        }
        return rows
    }

    @Test fun `an empty list stays put`() {
        assertEquals(0, PageJump.compute(1, 0, 0, null))
        assertEquals(3, PageJump.compute(-1, 0, 3, null))
    }

    @Test fun `with everything visible a jump goes to the end and stays`() {
        assertEquals(9, PageJump.compute(1, 10, 0, null))
        assertEquals(9, PageJump.compute(1, 10, 9, null))
        assertEquals(0, PageJump.compute(-1, 10, 9, null))
        assertEquals(0, PageJump.compute(-1, 10, 0, null))
    }

    @Test fun `an identity mapping matches supplying none`() {
        val identity = (0 until 10).toList()
        for (i in 0 until 10) {
            assertEquals(PageJump.compute(1, 10, i, null), PageJump.compute(1, 10, i, null, identity))
            assertEquals(PageJump.compute(-1, 10, i, null), PageJump.compute(-1, 10, i, null, identity))
        }
    }

    @Test fun `headers do not shorten the page when the whole list is visible`() {
        val rows = withHeaders(10)
        assertEquals(9, PageJump.compute(1, 10, 0, null, rows))
        assertEquals(0, PageJump.compute(-1, 10, 9, null, rows))
    }

    private fun translate(first: Int, last: Int, rows: List<Int>?, lastIndex: Int = 9) =
        PageJump.toSelectableSpace(PageJump.Viewport(first, last), rows, lastIndex)

    @Test fun `a partial viewport maps to the selectable rows inside it`() {
        val rows = withHeaders(10)
        assertEquals(PageJump.Viewport(0, 2), translate(1, 3, rows))
        assertEquals(PageJump.Viewport(3, 5), translate(5, 7, rows))
    }

    @Test fun `raw rendered bounds would have overshot`() {
        val rows = withHeaders(10)
        val translated = translate(5, 7, rows)
        assertEquals("the page is what fits on screen", 3, translated.last - translated.first + 1)
        assertEquals("not the rendered span, which counts a header", 3, 7 - 5 + 1)
        assertEquals(3, translated.first)
    }

    @Test fun `a viewport with no selectable row collapses to one position`() {
        val rows = listOf(1, 2, 3)
        val t = translate(4, 4, rows, lastIndex = 2)
        assertEquals(t.first, t.last)
    }

    @Test fun `a null mapping passes the viewport through untouched`() {
        assertEquals(PageJump.Viewport(2, 6), translate(2, 6, null))
    }
}
