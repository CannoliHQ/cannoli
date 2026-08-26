package dev.cannoli.scorza.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.computeScreenGeometryPadding
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenGeometryPaddingTest {

    private val ltr = LayoutDirection.Ltr

    @Test
    fun `pixel scale region reaches the panel's true right edge`() {
        // width 50% -> w = 1920*50/100 = 960; maxXPct = (100-50)/2 = 25, xPct 25 is unclamped
        // x = (1920-960)/2 + 1920*25/100 = 480 + 480 = 960, so right edge = x + w = 1920 exactly.
        // Density 1 keeps px and dp numerically identical, isolating this from unit conversion.
        val padding = computeScreenGeometryPadding(
            surfaceWidthPx = 1920,
            surfaceHeightPx = 1080,
            surfaceWidthDp = 1920,
            surfaceHeightDp = 1080,
            widthPct = 50,
            heightPct = 100,
            xPct = 25,
            yPct = 0,
            portraitMarginPx = 0,
            portrait = false,
            density = Density(density = 1f),
        )
        assertEquals(0.dp, padding.calculateRightPadding(ltr))
    }

    @Test
    fun `falls back to dp-derived region when pixel size is unavailable`() {
        // An early composition pass reports a zero-sized view. Must use the dp-derived rect
        // rather than computing off a zero-width, zero-height surface.
        // wPct=60 -> w=480; maxXPct=20, xPct=10 stays; x=(800-480)/2 + 800*10/100 = 160+80=240
        val padding = computeScreenGeometryPadding(
            surfaceWidthPx = 0,
            surfaceHeightPx = 0,
            surfaceWidthDp = 800,
            surfaceHeightDp = 480,
            widthPct = 60,
            heightPct = 100,
            xPct = 10,
            yPct = 0,
            portraitMarginPx = 0,
            portrait = false,
            density = Density(density = 2.5f),
        )
        assertEquals(240.dp, padding.calculateLeftPadding(ltr))
        assertEquals(80.dp, padding.calculateRightPadding(ltr))
    }

    @Test
    fun `portrait margin still reserves space at the bottom`() {
        val padding = computeScreenGeometryPadding(
            surfaceWidthPx = 1080,
            surfaceHeightPx = 1920,
            surfaceWidthDp = 1080,
            surfaceHeightDp = 1920,
            widthPct = 100,
            heightPct = 100,
            xPct = 0,
            yPct = 0,
            portraitMarginPx = 200,
            portrait = true,
            density = Density(density = 1f),
        )
        assertEquals(200.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `portrait margin ignored outside portrait`() {
        val padding = computeScreenGeometryPadding(
            surfaceWidthPx = 1920,
            surfaceHeightPx = 1080,
            surfaceWidthDp = 1920,
            surfaceHeightDp = 1080,
            widthPct = 100,
            heightPct = 100,
            xPct = 0,
            yPct = 0,
            portraitMarginPx = 200,
            portrait = false,
            density = Density(density = 1f),
        )
        assertEquals(0.dp, padding.calculateBottomPadding())
    }
}
