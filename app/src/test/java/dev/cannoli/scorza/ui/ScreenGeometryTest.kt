package dev.cannoli.scorza.ui

import dev.cannoli.ui.ScreenRect
import dev.cannoli.ui.computeScreenGeometryRect
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenGeometryTest {

    @Test
    fun `full screen default`() {
        assertEquals(
            ScreenRect(0, 0, 1920, 1080),
            computeScreenGeometryRect(1920, 1080, 100, 100, 0, 0),
        )
    }

    @Test
    fun `narrowed width centers`() {
        assertEquals(
            ScreenRect(240, 0, 1440, 1080),
            computeScreenGeometryRect(1920, 1080, 75, 100, 0, 0),
        )
    }

    @Test
    fun `offset shifts region right`() {
        assertEquals(
            ScreenRect(384, 0, 1536, 1080),
            computeScreenGeometryRect(1920, 1080, 80, 100, 10, 0),
        )
    }

    @Test
    fun `offset clamped so rect stays on surface`() {
        // width 60 -> max |x| = 20; x=30 clamps to 20, so x = (1000-600)/2 + 1000*20/100 = 400, x+w = 1000
        assertEquals(
            ScreenRect(400, 200, 600, 600),
            computeScreenGeometryRect(1000, 1000, 60, 60, 30, 0),
        )
    }

    @Test
    fun `size clamped to range`() {
        // width 200 clamps to 100, height 10 clamps to 50
        val r = computeScreenGeometryRect(1000, 1000, 200, 10, 0, 0)
        assertEquals(1000, r.w)
        assertEquals(500, r.h)
    }
}
