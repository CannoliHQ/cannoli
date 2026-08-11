package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class LegendResolverTest {
    private val r = LegendResolver()

    @Test fun `AYN Thor build model resolves nintendo plumber`() {
        val p = r.resolve(vendorId = 0x2020, productId = 0x0111, buildModel = "AYN_Thor")
        assertEquals(FaceLayout.NINTENDO, p.faceLayout)
        assertEquals(GlyphStyle.PLUMBER, p.glyphStyle)
    }

    @Test fun `AYN Odin Portal build model resolves standard redmond`() {
        val p = r.resolve(vendorId = 0x2020, productId = 0x0111, buildModel = "AYN_Odin_Portal")
        assertEquals(FaceLayout.STANDARD, p.faceLayout)
        assertEquals(GlyphStyle.REDMOND, p.glyphStyle)
    }

    @Test fun `sony vid gives shapes on standard layout, model unknown`() {
        val p = r.resolve(vendorId = 0x054C, productId = 0x0CE6, buildModel = "Pixel 7")
        assertEquals(FaceLayout.STANDARD, p.faceLayout)
        assertEquals(GlyphStyle.SHAPES, p.glyphStyle)
    }

    @Test fun `unknown pad defaults to standard redmond`() {
        val p = r.resolve(vendorId = 0x1234, productId = 0x5678, buildModel = "Some Phone")
        assertEquals(FaceLayout.STANDARD, p.faceLayout)
        assertEquals(GlyphStyle.REDMOND, p.glyphStyle)
    }

    @Test fun `build model wins over sony vid when both match`() {
        val p = r.resolve(vendorId = 0x054C, productId = 0x0001, buildModel = "AYN_Thor")
        assertEquals(FaceLayout.NINTENDO, p.faceLayout)
        assertEquals(GlyphStyle.PLUMBER, p.glyphStyle)
    }
}
