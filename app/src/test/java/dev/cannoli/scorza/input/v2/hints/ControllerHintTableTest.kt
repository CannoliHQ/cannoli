package dev.cannoli.scorza.input.v2.hints

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerHintTableTest {

    private val json = """
        {
          "vid_pid": [
            { "vendor_id": 1356, "menuConfirm": "BTN_SOUTH", "glyphStyle": "SHAPES" },
            { "vendor_id": 1356, "product_id": 2508, "menuConfirm": "BTN_EAST", "glyphStyle": "PLUMBER" }
          ],
          "build_model": [
            { "model_prefix": "RP_CLASSIC", "menuConfirm": "BTN_EAST", "glyphStyle": "PLUMBER" }
          ],
          "default": { "menuConfirm": "BTN_SOUTH", "glyphStyle": "REDMOND" }
        }
    """.trimIndent()

    private val table = ControllerHintTable.fromJson(json)

    @Test fun `vid+pid exact match wins over vid-only`() {
        val hint = table.lookup(vendorId = 1356, productId = 2508, buildModel = "RP_CLASSIC")
        assertEquals(CanonicalButton.BTN_EAST, hint.menuConfirm)
        assertEquals(GlyphStyle.PLUMBER, hint.glyphStyle)
    }

    @Test fun `vid-only matches when no vid+pid hint exists`() {
        val hint = table.lookup(vendorId = 1356, productId = 9999, buildModel = "")
        assertEquals(CanonicalButton.BTN_SOUTH, hint.menuConfirm)
        assertEquals(GlyphStyle.SHAPES, hint.glyphStyle)
    }

    @Test fun `build model prefix matches when no vid hint`() {
        val hint = table.lookup(vendorId = 0, productId = 0, buildModel = "RP_CLASSIC_v2")
        assertEquals(CanonicalButton.BTN_EAST, hint.menuConfirm)
        assertEquals(GlyphStyle.PLUMBER, hint.glyphStyle)
    }

    @Test fun `default applies when nothing matches`() {
        val hint = table.lookup(vendorId = 0, productId = 0, buildModel = "Pixel 7")
        assertEquals(CanonicalButton.BTN_SOUTH, hint.menuConfirm)
        assertEquals(GlyphStyle.REDMOND, hint.glyphStyle)
    }

    @Test fun `vid match beats build model match`() {
        val hint = table.lookup(vendorId = 1356, productId = 0, buildModel = "RP_CLASSIC")
        assertEquals(GlyphStyle.SHAPES, hint.glyphStyle)
    }
}
