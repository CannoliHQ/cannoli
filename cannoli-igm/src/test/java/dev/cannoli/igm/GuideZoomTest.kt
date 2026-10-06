package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideZoomTest {
    @Test fun scalesAndFontsAreIndexParallel() {
        assertEquals(GuideZoom.pdfScales.size, GuideZoom.txtFontSizes.size)
        assertEquals(GuideZoom.pdfScales.size, GuideZoom.levels)
    }

    @Test fun addsStepsBetweenNoneAndTwoX() {
        val betweenOneAndTwo = GuideZoom.pdfScales.filter { it > 1.0f && it < 2.0f }
        assertTrue(betweenOneAndTwo.size >= 2)
    }
}
