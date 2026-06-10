package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformMapTest {

    private val slugMap = RommSlugMap.parse("""{"snes":"SNES","gba":"GBA","weirdo":"WEIRDO"}""")

    private fun dto(id: Int, slug: String, name: String, count: Int) =
        PlatformDto(id = id, slug = slug, name = name, displayName = name, romCount = count)

    @Test fun `maps known and supported platforms only`() {
        val map = PlatformMap(slugMap, isSupported = { it == "SNES" || it == "GBA" })
        val result = map.toDomain(listOf(
            dto(1, "snes", "Super Nintendo", 5),
            dto(2, "gba", "Game Boy Advance", 9),
            dto(3, "weirdo", "Unsupported Core", 2),
            dto(4, "unmapped", "No Slug", 1),
        ))
        assertEquals(listOf("SNES", "GBA"), result.map { it.cannoliTag })
        assertEquals(5, result.first().romCount)
        assertEquals("Super Nintendo", result.first().displayName)
    }

    @Test fun `empty when nothing supported`() {
        val map = PlatformMap(slugMap, isSupported = { false })
        assertEquals(emptyList<RommPlatform>(), map.toDomain(listOf(dto(1, "snes", "SNES", 1))))
    }
}
