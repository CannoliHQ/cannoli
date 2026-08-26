package dev.cannoli.scorza.ui

import dev.cannoli.ricotta.ViewportController
import dev.cannoli.ricotta.ViewportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportControllerTest {

    private class FakeBridge(
        var geometry: IntArray? = intArrayOf(320, 240, 13333, 10000),
    ) {
        var applied: IntArray? = null
        var cleared: Int? = null
        fun apply(x: Int, y: Int, w: Int, h: Int): Boolean {
            applied = intArrayOf(x, y, w, h); return true
        }
        fun clear(idx: Int): Boolean { cleared = idx; return true }
    }

    private fun controller(
        b: FakeBridge,
        marginPx: Int = 0,
        wPct: Int = 100, hPct: Int = 100, xPct: Int = 0, yPct: Int = 0,
        aspectIdx: Int = 22, integer: Boolean = false,
    ) = ViewportController(
        coreGeometry = { b.geometry },
        applyViewport = b::apply,
        clearViewport = b::clear,
        readAspectIdx = { aspectIdx },
        readIntegerScale = { integer },
        settings = ViewportSettings(marginPx, wPct, hPct, xPct, yPct),
    )

    @Test fun `defaults apply nothing and leave the aspect index alone`() {
        val b = FakeBridge()
        assertFalse(controller(b).refresh(1920, 1080))
        assertEquals(null, b.applied)
        assertEquals(null, b.cleared)
    }

    @Test fun `a geometry change applies a viewport`() {
        val b = FakeBridge()
        assertTrue(controller(b, wPct = 80).refresh(1920, 1080))
        assertEquals(4, b.applied!!.size)
    }

    @Test fun `a margin applies a viewport only in portrait`() {
        val land = FakeBridge()
        assertFalse(controller(land, marginPx = 200).refresh(1920, 1080))
        val port = FakeBridge()
        assertTrue(controller(port, marginPx = 200).refresh(1080, 1920))
    }

    @Test fun `no core geometry declines rather than guessing`() {
        val b = FakeBridge(geometry = null)
        assertFalse(controller(b, wPct = 80).refresh(1920, 1080))
        assertEquals(null, b.applied)
    }

    @Test fun `aspect index 24 means fill the region`() {
        val b = FakeBridge()
        controller(b, wPct = 50, aspectIdx = 24).refresh(1920, 1080)
        assertEquals(960, b.applied!![2])
        assertEquals(1080, b.applied!![3])
    }

    @Test fun `integer scale maps to whole multiples`() {
        val b = FakeBridge()
        controller(b, wPct = 75, aspectIdx = 22, integer = true).refresh(1920, 1080)
        assertEquals(1280, b.applied!![2])
    }

    // Once a viewport is live, returning the settings to their defaults must hand the index back.
    @Test fun `returning to defaults clears the viewport`() {
        val b = FakeBridge()
        val live = controller(b, wPct = 80)
        assertTrue(live.refresh(1920, 1080))
        val back = controller(b, wPct = 100)
        back.markActive()
        assertFalse(back.refresh(1920, 1080))
        assertEquals(22, b.cleared)
    }
}
