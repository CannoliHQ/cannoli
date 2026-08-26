package dev.cannoli.scorza.ui

import dev.cannoli.ui.ScalingMode
import dev.cannoli.ui.ViewportRect
import dev.cannoli.ui.computeViewport
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportGeometryTest {

    private fun vp(
        surfaceWidth: Int = 1920,
        surfaceHeight: Int = 1080,
        frameWidth: Int = 320,
        frameHeight: Int = 240,
        coreAspectRatio: Float = 0f,
        rotation: Int = 0,
        scalingMode: ScalingMode = ScalingMode.CORE_REPORTED,
        portraitMarginPx: Int = 0,
        geometryWidthPct: Int = 100,
        geometryHeightPct: Int = 100,
        geometryXPct: Int = 0,
        geometryYPct: Int = 0,
    ) = computeViewport(
        surfaceWidth, surfaceHeight, frameWidth, frameHeight, coreAspectRatio, rotation,
        scalingMode, portraitMarginPx, geometryWidthPct, geometryHeightPct, geometryXPct, geometryYPct,
    )

    // The no-op path the guard in Task 3 depends on: defaults must letterbox 4:3 in 16:9.
    @Test fun `defaults letterbox the game centred`() =
        assertEquals(ViewportRect(240, 0, 1440, 1080), vp())

    @Test fun `margin is ignored in landscape`() =
        assertEquals(vp(), vp(portraitMarginPx = 200))

    // Portrait: 1080 wide, 1920 tall, 200px reserved at the bottom.
    // effTop = 0, effH = 1720, 4:3 fits to width, so 1080x810, centred in the region above the margin.
    @Test fun `margin shrinks the picture in portrait`() {
        val r = vp(surfaceWidth = 1080, surfaceHeight = 1920, portraitMarginPx = 200)
        assertEquals(1080, r.w)
        assertEquals(810, r.h)
        assertEquals(0 + (1720 - 810) / 2, r.y)
    }

    @Test fun `margin never distorts the picture`() {
        val a = vp(surfaceWidth = 1080, surfaceHeight = 1920)
        val b = vp(surfaceWidth = 1080, surfaceHeight = 1920, portraitMarginPx = 400)
        assertEquals(a.w.toFloat() / a.h, b.w.toFloat() / b.h, 0.01f)
    }

    @Test fun `fullscreen fills the region exactly`() =
        assertEquals(ViewportRect(240, 108, 1440, 864), vp(
            scalingMode = ScalingMode.FULLSCREEN,
            geometryWidthPct = 75, geometryHeightPct = 80,
        ))

    // 1440x1080 region, 320x240 frame: floor(min(4.5, 4.5)) = 4, so 1280x960.
    @Test fun `integer scales by whole multiples inside the region`() {
        val r = vp(scalingMode = ScalingMode.INTEGER, geometryWidthPct = 75)
        assertEquals(1280, r.w)
        assertEquals(960, r.h)
    }

    @Test fun `region and margin compose rather than replacing each other`() {
        val r = vp(
            surfaceWidth = 1080, surfaceHeight = 1920,
            scalingMode = ScalingMode.FULLSCREEN,
            portraitMarginPx = 200, geometryHeightPct = 50,
        )
        assertEquals(960 - 200, r.h)
    }

    @Test fun `core reported aspect wins over the frame aspect`() {
        val r = vp(frameWidth = 256, frameHeight = 224, coreAspectRatio = 4f / 3f)
        assertEquals(1440, r.w)
        assertEquals(1080, r.h)
    }

    @Test fun `a rotated core swaps the aspect`() {
        val r = vp(frameWidth = 320, frameHeight = 240, rotation = 1)
        assertEquals(810, r.w)
        assertEquals(1080, r.h)
    }

    // Region: h = 540 (50%), y = (1080-540)/2 + 1080*-10/100 = 270-108 = 162, so effTop = 162.
    // 4:3 fits to height inside 1920x540 (screen is wider than 4:3), giving 720x540, so
    // outH == effH and y is untouched: a bottom-left regression would instead put y at 378.
    @Test fun `a y offset in the region lands top-left, not bottom-left`() =
        assertEquals(ViewportRect(600, 162, 720, 540), vp(geometryHeightPct = 50, geometryYPct = -10))
}
