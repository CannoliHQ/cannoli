package dev.cannoli.scorza.ui

import dev.cannoli.ricotta.ViewportController
import dev.cannoli.ricotta.ViewportController.RefreshResult
import dev.cannoli.ricotta.ViewportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewportControllerTest {

    private class FakeBridge(
        var geometry: IntArray? = intArrayOf(320, 240, 13333, 10000),
        // Mutable so a test can change what these read back between two refresh() calls, the way
        // a successful apply forces both to Cannoli's takeover values on the native side.
        var aspectIdx: Int = 22,
        var integerScale: Boolean = false,
        var aspectValue: Float = 0f,
    ) {
        var applied: IntArray? = null
        var clearedAspectIdx: Int? = null
        var clearedIntegerScale: Boolean? = null
        var geometryReads = 0
        fun apply(x: Int, y: Int, w: Int, h: Int): Boolean {
            applied = intArrayOf(x, y, w, h); return true
        }
        fun clear(idx: Int, integer: Boolean): Boolean {
            clearedAspectIdx = idx; clearedIntegerScale = integer; return true
        }
    }

    private fun controller(
        b: FakeBridge,
        marginPx: Int = 0,
        wPct: Int = 100, hPct: Int = 100, xPct: Int = 0, yPct: Int = 0,
        aspectIdx: Int = 22, integer: Boolean = false,
    ): ViewportController {
        b.aspectIdx = aspectIdx
        b.integerScale = integer
        return ViewportController(
            coreGeometry = { b.geometryReads++; b.geometry },
            applyViewport = b::apply,
            clearViewport = b::clear,
            readAspectIdx = { b.aspectIdx },
            readIntegerScale = { b.integerScale },
            readAspectValue = { b.aspectValue },
            settings = ViewportSettings(marginPx, wPct, hPct, xPct, yPct),
        )
    }

    @Test fun `defaults apply nothing and leave the aspect index alone`() {
        val b = FakeBridge()
        assertEquals(RefreshResult.DECLINED, controller(b).refresh(1920, 1080))
        assertEquals(null, b.applied)
        assertEquals(null, b.clearedAspectIdx)
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

    // The previous version of this test forced "active with nothing wanted" via markActive() on a
    // fresh controller, which leaves shadowedAspectIdx unset even though active is true - a
    // combination refresh() can never produce. ViewportSettings is fixed per controller instance,
    // so geometryWanted never changes within one instance's lifetime; the only way a live controller
    // actually sees both marginWanted and geometryWanted go false is rotating out of portrait when
    // only the margin wanted a viewport. That is what actually drives the clear branch in
    // production, and the clear must hand back what was captured before the apply, not RetroArch's
    // live (forced-to-custom) values.
    @Test fun `rotating out of portrait clears a margin-only viewport with the captured values`() {
        val b = FakeBridge()
        val c = controller(b, marginPx = 200, aspectIdx = 24, integer = true)
        assertEquals(RefreshResult.APPLIED, c.refresh(1080, 1920))

        // The apply just ran forces these on the native side; the fake has to be told explicitly
        // since it does not simulate the native force itself.
        b.aspectIdx = 23
        b.integerScale = false

        assertEquals(RefreshResult.DECLINED, c.refresh(1920, 1080))
        assertEquals(24, b.clearedAspectIdx)
        assertEquals(true, b.clearedIntegerScale)
    }

    @Test fun `the clear restores the captured aspect index rather than the live one`() {
        val b = FakeBridge()
        val c = controller(b, marginPx = 200, aspectIdx = 24)
        assertEquals(RefreshResult.APPLIED, c.refresh(1080, 1920))

        b.aspectIdx = 23
        assertEquals(RefreshResult.DECLINED, c.refresh(1920, 1080))
        assertEquals(24, b.clearedAspectIdx)
    }

    @Test fun `the clear restores video_scale_integer to its captured value`() {
        val b = FakeBridge()
        val c = controller(b, marginPx = 200, aspectIdx = 22, integer = true)
        assertEquals(RefreshResult.APPLIED, c.refresh(1080, 1920))

        b.integerScale = false
        assertEquals(RefreshResult.DECLINED, c.refresh(1920, 1080))
        assertEquals(true, b.clearedIntegerScale)
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

    // The launcher writes the scaling row through a queued command while this reads settings
    // synchronously, so a refresh fired on menu dismissal can read the value from before the
    // change. Once a viewport is live the index reads as custom and the remembered mode wins on
    // every later refresh, so a stale latch never corrects itself. What rescues it is refreshing
    // again once the write has actually landed, which is when the index reflects the new choice.
    @Test fun `a refresh after the write lands replaces a mode latched from a stale read`() {
        val b = FakeBridge()
        // The user picked fullscreen, but the queued write has not drained: the read still says 22.
        val c = controller(b, wPct = 50, aspectIdx = 22)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        val coreReported = b.applied!!.toList()

        // The write lands, so the index now says what the user chose, and the echo drives a refresh.
        b.aspectIdx = 24
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        val afterEcho = b.applied!!.toList()

        val fullscreen = FakeBridge()
        controller(fullscreen, wPct = 50, aspectIdx = 24).refresh(1920, 1080)
        assertEquals("the landed choice has to win", fullscreen.applied!!.toList(), afterEcho)
        assertNotEquals("otherwise the stale read is what stuck", coreReported, afterEcho)
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

    @Test fun `shadowedSettings is empty before any apply`() {
        val b = FakeBridge()
        assertEquals(emptyMap<String, String>(), controller(b, wPct = 80).shadowedSettings())
    }

    // The apply forces aspect_ratio_index to ASPECT_RATIO_CUSTOM (23) on the native side, so what
    // shadowedSettings() hands back has to be captured from the read that happened before that,
    // not read back afterwards.
    @Test fun `shadowedSettings returns the pre-apply index and integer-scale values while active`() {
        val b = FakeBridge()
        val c = controller(b, wPct = 80, aspectIdx = 22, integer = true)
        assertEquals(RefreshResult.APPLIED, c.refresh(1920, 1080))
        assertEquals(
            mapOf("aspect_ratio_index" to "22", "video_scale_integer" to "true"),
            c.shadowedSettings(),
        )
    }

    // Mirrors `rotating out of portrait clears a margin-only viewport with the captured values`:
    // a second controller stands in for the same session after Screen Geometry settings changed
    // back to defaults.
    @Test fun `shadowedSettings is empty again once the viewport clears`() {
        val b = FakeBridge()
        controller(b, wPct = 80).refresh(1920, 1080)
        val back = controller(b, wPct = 100)
        back.markActive()
        assertEquals(RefreshResult.DECLINED, back.refresh(1920, 1080))
        assertEquals(emptyMap<String, String>(), back.shadowedSettings())
    }
}
