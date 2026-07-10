package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportGeometryTest {

    @Test
    fun `default full landscape centers letterboxed`() {
        val vp = computeViewport(
            surfaceWidth = 1920, surfaceHeight = 1080,
            frameWidth = 320, frameHeight = 240,
            coreAspectRatio = 0f, rotation = 0,
            scalingMode = ScalingMode.CORE_REPORTED,
            portraitMarginPx = 0,
            geometryWidthPct = 100, geometryHeightPct = 100, geometryXPct = 0, geometryYPct = 0,
        )
        assertEquals(ViewportRect(240, 0, 1440, 1080), vp)
    }

    @Test
    fun `narrowed width fits four-three region`() {
        val vp = computeViewport(
            surfaceWidth = 1920, surfaceHeight = 1080,
            frameWidth = 320, frameHeight = 240,
            coreAspectRatio = 0f, rotation = 0,
            scalingMode = ScalingMode.CORE_REPORTED,
            portraitMarginPx = 0,
            geometryWidthPct = 75, geometryHeightPct = 100, geometryXPct = 0, geometryYPct = 0,
        )
        assertEquals(ViewportRect(240, 0, 1440, 1080), vp)
    }

    @Test
    fun `horizontal offset shifts the fitted game`() {
        val vp = computeViewport(
            surfaceWidth = 1920, surfaceHeight = 1080,
            frameWidth = 320, frameHeight = 240,
            coreAspectRatio = 0f, rotation = 0,
            scalingMode = ScalingMode.CORE_REPORTED,
            portraitMarginPx = 0,
            geometryWidthPct = 80, geometryHeightPct = 100, geometryXPct = 10, geometryYPct = 0,
        )
        assertEquals(ViewportRect(432, 0, 1440, 1080), vp)
    }

    @Test
    fun `default portrait margin adds to bottom`() {
        val vp = computeViewport(
            surfaceWidth = 1080, surfaceHeight = 1920,
            frameWidth = 240, frameHeight = 320,
            coreAspectRatio = 0f, rotation = 0,
            scalingMode = ScalingMode.CORE_REPORTED,
            portraitMarginPx = 100,
            geometryWidthPct = 100, geometryHeightPct = 100, geometryXPct = 0, geometryYPct = 0,
        )
        assertEquals(ViewportRect(0, 290, 1080, 1440), vp)
    }

    @Test
    fun `extreme values never degenerate`() {
        val vp = computeViewport(
            surfaceWidth = 1920, surfaceHeight = 1080,
            frameWidth = 320, frameHeight = 240,
            coreAspectRatio = 0f, rotation = 0,
            scalingMode = ScalingMode.CORE_REPORTED,
            portraitMarginPx = 0,
            geometryWidthPct = 50, geometryHeightPct = 50, geometryXPct = 25, geometryYPct = 25,
        )
        assertTrue(vp.w >= 1)
        assertTrue(vp.h >= 1)
    }
}
