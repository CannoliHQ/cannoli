package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class LegendResolverTest {
    private val r = LegendResolver()

    @Test fun `sony vid gives shapes on standard layout`() {
        val p = r.resolve(vendorId = 0x054C, productId = 0x0CE6)
        assertEquals(FaceLayout.STANDARD, p.faceLayout)
        assertEquals(GlyphStyle.SHAPES, p.glyphStyle)
    }

    @Test fun `unknown pad defaults to standard redmond`() {
        val p = r.resolve(vendorId = 0x1234, productId = 0x5678)
        assertEquals(FaceLayout.STANDARD, p.faceLayout)
        assertEquals(GlyphStyle.REDMOND, p.glyphStyle)
    }
}
