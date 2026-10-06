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
