package dev.cannoli.igm

import dev.cannoli.ui.ButtonLabelSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegaPadLayoutsTest {

    private val u = ButtonRemap.UNBOUND
    private fun routed(core: String) = SegaPadLayouts.routed(core, ButtonLabelSet.HEDGEHOG_6)

    @Test fun `Genesis Plus GX reads C from A and Z from R, and has no shoulders`() {
        val expected = mapOf(8 to 1, 0 to 0, 14 to 8, 9 to 10, 1 to 9, 15 to 11, 10 to u, 12 to u, 11 to u, 13 to u)
        assertEquals(expected, routed("genesis_plus_gx_libretro"))
        assertEquals(expected, routed("genesis_plus_gx_wide_libretro"))
        assertEquals(expected, routed("picodrive_libretro"))
    }

    @Test fun `Beetle Saturn reads C from R and Z from L`() {
        assertEquals(
            mapOf(8 to 0, 0 to 8, 14 to 11, 9 to 1, 1 to 9, 15 to 10, 10 to 12, 12 to 12, 11 to 13, 13 to 13),
            routed("mednafen_saturn_libretro"),
        )
    }

    @Test fun `yabasanshiro reads C from L and Z from R`() {
        assertEquals(
            mapOf(8 to 0, 0 to 8, 14 to 10, 9 to 1, 1 to 9, 15 to 11, 10 to 12, 12 to 12, 11 to 13, 13 to 13),
            routed("yabasanshiro_libretro"),
        )
    }

    @Test fun `a core not in the table routes nothing`() {
        assertTrue(routed("snes9x_libretro").isEmpty())
        assertTrue(routed("some_future_sega_libretro").isEmpty())
    }

    @Test fun `a pad that is not Sega routes nothing`() {
        assertTrue(SegaPadLayouts.routed("genesis_plus_gx_libretro", ButtonLabelSet.PLUMBER).isEmpty())
    }
}
