package dev.cannoli.scorza.ui

import dev.cannoli.ricotta.ViewportController
import dev.cannoli.ricotta.ViewportController.RefreshResult
import dev.cannoli.ricotta.ViewportSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportControllerTest {

    private class FakeBridge(
        var geometry: IntArray? = intArrayOf(320, 240, 13333, 10000),
        // Mutable so a test can change what the index reads back between two refresh() calls,
        // the way a successful apply forces it to ASPECT_RATIO_CUSTOM on the native side.
        var aspectIdx: Int = 22,
        var aspectValue: Float = 0f,
    ) {
        var applied: IntArray? = null
        var cleared: Int? = null
        var geometryReads = 0
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
    ): ViewportController {
        b.aspectIdx = aspectIdx
        return ViewportController(
            coreGeometry = { b.geometryReads++; b.geometry },
            applyViewport = b::apply,
            clearViewport = b::clear,
            readAspectIdx = { b.aspectIdx },
            readIntegerScale = { integer },
            readAspectValue = { b.aspectValue },
            settings = ViewportSettings(marginPx, wPct, hPct, xPct, yPct),
        )
    }

    @Test fun `defaults apply nothing and leave the aspect index alone`() {
        val b = FakeBridge()
        assertEquals(RefreshResult.DECLINED, controller(b).refresh(1920, 1080))
        assertEquals(null, b.applied)
        assertEquals(null, b.cleared)
    }

    @Test fun `defaults decline before ever asking the core for geometry`() {
        val b = FakeBridge()
        assertEquals(RefreshResult.DECLINED, controller(b).refresh(1920, 1080))
        assertEquals(0, b.geometryReads)
    }

    @Test fun `a geometry change applies a viewport`() {
        val b = FakeBridge()
        assertEquals(RefreshResult.APPLIED, controller(b, wPct = 80).refresh(1920, 1080))
        assertEquals(4, b.applied!!.size)
    }

    @Test fun `a margin applies a viewport only in portrait`() {
        val land = FakeBridge()
        assertEquals(RefreshResult.DECLINED, controller(land, marginPx = 200).refresh(1920, 1080))
        val port = FakeBridge()
        assertEquals(RefreshResult.APPLIED, controller(port, marginPx = 200).refresh(1080, 1920))
    }

    @Test fun `no core geometry yields not-ready rather than a plain decline`() {
        val b = FakeBridge(geometry = null)
        assertEquals(RefreshResult.NOT_READY, controller(b, wPct = 80).refresh(1920, 1080))
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
        assertEquals(RefreshResult.APPLIED, live.refresh(1920, 1080))
        val back = controller(b, wPct = 100)
        back.markActive()
        assertEquals(RefreshResult.DECLINED, back.refresh(1920, 1080))
        assertEquals(22, b.cleared)
    }

    // A successful apply forces aspect_ratio_index to ASPECT_RATIO_CUSTOM (23) on the native
    // side, so a naive re-derivation on the next refresh would lose the user's chosen mode.

    @Test fun `a fullscreen user stays fullscreen once the apply forces the index to custom`() {
        val b = FakeBridge()
        val c = controller(b, wPct = 50, aspectIdx = 24)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(960, b.applied!![2])
        assertEquals(1080, b.applied!![3])

        b.aspectIdx = 23
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(960, b.applied!![2])
        assertEquals(1080, b.applied!![3])
    }

    @Test fun `an integer user stays integer once the apply forces the index to custom`() {
        val b = FakeBridge()
        val c = controller(b, wPct = 75, aspectIdx = 22, integer = true)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(1280, b.applied!![2])

        b.aspectIdx = 23
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(1280, b.applied!![2])
    }

    @Test fun `an index of 23 with nothing remembered falls back to core reported`() {
        val reported = FakeBridge()
        controller(reported, wPct = 50, aspectIdx = 22).refresh(1920, 1080)

        val b = FakeBridge()
        val c = controller(b, wPct = 50, aspectIdx = 23)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(reported.applied!!.toList(), b.applied!!.toList())
    }

    // Region: wPct 50 on 1920x1080 -> 960x1080. Index 0 (4:3) is neither 22 nor 24, so the fit
    // must use the LUT's 16:9 rather than the core's own ~4:3 (13333/10000): screenAspect is
    // 960/1080 = 0.89, narrower than 16:9, so w = effW = 960, h = 960 / (16/9) = 540. The core's
    // own aspect would instead give h = 720.
    @Test fun `an index outside 22 and 24 uses the LUT value instead of the core's own aspect`() {
        val b = FakeBridge()
        b.aspectValue = 16f / 9f
        val c = controller(b, wPct = 50, aspectIdx = 0)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(960, b.applied!![2])
        assertEquals(540, b.applied!![3])
    }
}
