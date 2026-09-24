package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippedCoreOptionsTest {

    private fun compose(
        core: String = "picodrive_libretro",
        style: GlyphStyle? = GlyphStyle.HEDGEHOG_6,
        platform: Map<String, String> = emptyMap(),
        game: Map<String, String> = emptyMap(),
    ) = ShippedCoreOptions.compose(core, style, platform, game)

    @Test fun `a Sega 6-Button pad gets a six-button PicoDrive on both ports`() {
        val options = compose()
        assertEquals("6 button pad", options["picodrive_input1"])
        assertEquals("6 button pad", options["picodrive_input2"])
    }

    @Test fun `any other pad leaves PicoDrive on its own default`() {
        assertTrue(compose(style = GlyphStyle.PLUMBER).isEmpty())
        assertTrue(compose(style = null).isEmpty())
    }

    @Test fun `no other core is touched`() {
        assertTrue(compose(core = "genesis_plus_gx_libretro").isEmpty())
    }

    @Test fun `the user's own option wins`() {
        val options = compose(platform = mapOf("picodrive_input1" to "3 button pad"))
        assertEquals("3 button pad", options["picodrive_input1"])
        assertEquals("6 button pad", options["picodrive_input2"])
    }

    @Test fun `the game tier beats the platform tier as before`() {
        val options = compose(
            style = null,
            platform = mapOf("picodrive_renderer" to "fast"),
            game = mapOf("picodrive_renderer" to "accurate"),
        )
        assertEquals("accurate", options["picodrive_renderer"])
    }
}
