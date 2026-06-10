package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommMetadatumMappingTest {

    @Test fun `toDomain maps metadatum fields`() {
        val dto = SimpleRomDto(
            id = 1, platformId = 2, fsName = "ff6.sfc",
            metadatum = RomMetadatumDto(
                genres = listOf("Role-playing (RPG)"),
                companies = listOf("Square"),
                gameModes = listOf("Single player"),
                firstReleaseDate = 765072000000L,
            ),
        )
        val game = dto.toDomain()
        assertEquals(listOf("Square"), game.companies)
        assertEquals(listOf("Role-playing (RPG)"), game.genres)
        assertEquals(listOf("Single player"), game.gameModes)
        assertEquals(765072000000L, game.firstReleaseDate)
    }

    @Test fun `toDomain tolerates missing metadatum`() {
        val game = SimpleRomDto(id = 1, platformId = 2, fsName = "x.sfc").toDomain()
        assertEquals(emptyList<String>(), game.companies)
        assertEquals(emptyList<String>(), game.genres)
        assertEquals(emptyList<String>(), game.gameModes)
        assertEquals(null, game.firstReleaseDate)
    }
}
