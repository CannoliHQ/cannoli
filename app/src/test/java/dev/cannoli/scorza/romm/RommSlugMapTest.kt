package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RommSlugMapTest {

    @Test fun `parses slug to tag map from json`() {
        val map = RommSlugMap.parse("""{"snes":"SNES","gba":"GBA"}""")
        assertEquals("SNES", map.tagForSlug("snes"))
        assertEquals("GBA", map.tagForSlug("gba"))
    }

    @Test fun `unknown slug returns null`() {
        val map = RommSlugMap.parse("""{"snes":"SNES"}""")
        assertNull(map.tagForSlug("dreamcast-vmu"))
    }

    @Test fun `slug lookup is case-insensitive`() {
        val map = RommSlugMap.parse("""{"snes":"SNES"}""")
        assertEquals("SNES", map.tagForSlug("SNES"))
    }

    @Test fun `bundled romm_platforms asset is valid and maps known slugs`() {
        val asset = File("src/main/assets/romm_platforms.json")
        assertEquals(true, asset.exists())
        val map = RommSlugMap.parse(asset.readText())
        assertEquals("SNES", map.tagForSlug("snes"))
        assertEquals("GENESIS", map.tagForSlug("genesis-slash-megadrive"))
    }
}
