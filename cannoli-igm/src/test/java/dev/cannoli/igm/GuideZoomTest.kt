package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideZoomTest {
    @Test fun scalesAndFontsAreIndexParallel() {
        assertEquals(GuideZoom.pdfScales.size, GuideZoom.txtFontSizes.size)
        assertEquals(GuideZoom.pdfScales.size, GuideZoom.levels)
    }

    @Test fun startsAtNoneAndKeepsTwoAndThreeX() {
        assertEquals(1.0f, GuideZoom.pdfScales.first())
        assertTrue(GuideZoom.pdfScales.contains(2.0f))
        assertTrue(GuideZoom.pdfScales.contains(3.0f))
        assertEquals(14, GuideZoom.txtFontSizes.first())
    }

    @Test fun addsStepsBetweenNoneAndTwoX() {
        val betweenOneAndTwo = GuideZoom.pdfScales.filter { it > 1.0f && it < 2.0f }
        assertTrue(betweenOneAndTwo.size >= 2)
    }
}
