package dev.cannoli.scorza.ui

import dev.cannoli.ui.ScalingMode
import dev.cannoli.ui.ViewportRect
import dev.cannoli.ui.computeViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportGeometryTest {

    private fun vp(
        surfaceWidth: Int = 1920,
        surfaceHeight: Int = 1080,
        frameWidth: Int = 320,
        frameHeight: Int = 240,
        coreAspectRatio: Float = 0f,
        requestedAspect: Float = 0f,
        rotation: Int = 0,
        scalingMode: ScalingMode = ScalingMode.CORE_REPORTED,
        portraitMarginPx: Int = 0,
        geometryWidthPct: Int = 100,
        geometryHeightPct: Int = 100,
        geometryXPct: Int = 0,
        geometryYPct: Int = 0,
    ) = computeViewport(
        surfaceWidth, surfaceHeight, frameWidth, frameHeight, coreAspectRatio, requestedAspect,
        rotation, scalingMode, portraitMarginPx, geometryWidthPct, geometryHeightPct, geometryXPct,
        geometryYPct,
    )

    @Test fun `defaults letterbox the game centred`() =
        assertEquals(ViewportRect(240, 0, 1440, 1080), vp())

    @Test fun `margin is ignored in landscape`() =
        assertEquals(vp(), vp(portraitMarginPx = 200))

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

    @Test fun `a y offset in the region lands top-left, not bottom-left`() =
        assertEquals(ViewportRect(600, 162, 720, 540), vp(geometryHeightPct = 50, geometryYPct = -10))

    @Test fun `an integer scale that cannot fit at 1x falls back to an aspect fit inside the region`() {
        val r = vp(
            frameWidth = 2048, frameHeight = 1912,
            scalingMode = ScalingMode.INTEGER,
            geometryWidthPct = 57, geometryHeightPct = 80,
            geometryXPct = 21, geometryYPct = 10,
        )
        assertTrue(r.x >= 816)
        assertTrue(r.y >= 216)
        assertTrue(r.x + r.w <= 816 + 1094)
        assertTrue(r.y + r.h <= 216 + 864)
    }

    @Test fun `an integer scale that does fit still scales by whole multiples`() {
        val r = vp(
            frameWidth = 320, frameHeight = 240,
            scalingMode = ScalingMode.INTEGER,
            geometryWidthPct = 57, geometryHeightPct = 80,
            geometryXPct = 21, geometryYPct = 10,
        )
        assertEquals(960, r.w)
        assertEquals(720, r.h)
    }

    @Test fun `a requested aspect is honoured over the core's own`() {
        val r = vp(
            frameWidth = 2048, frameHeight = 1912,
            requestedAspect = 4f / 3f,
            geometryWidthPct = 57, geometryHeightPct = 80,
            geometryXPct = 21, geometryYPct = 10,
        )
        assertEquals(4f / 3f, r.w.toFloat() / r.h, 0.01f)
    }
}
