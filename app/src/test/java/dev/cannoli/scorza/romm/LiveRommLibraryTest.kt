package dev.cannoli.scorza.romm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRommLibraryTest {

    private val slugMap = RommSlugMap.parse("""{"snes":"SNES"}""")
    private val platformMap = PlatformMap(slugMap, isSupported = { true })

    @Test fun `platforms maps and filters client dtos`() = runTest {
        val client = mockk<RommClient>()
        every { client.getPlatforms() } returns listOf(
            PlatformDto(id = 1, slug = "snes", name = "SNES", displayName = "SNES", romCount = 4),
            PlatformDto(id = 2, slug = "unmapped", name = "X", displayName = "X", romCount = 1),
        )
        val lib = LiveRommLibrary(client, platformMap)
        val result = lib.platforms()
        assertEquals(listOf("SNES"), result.map { it.cannoliTag })
    }

    @Test fun `games maps page items to domain`() = runTest {
        val client = mockk<RommClient>()
        every { client.getRoms(12, 100, 0, null) } returns RomsPageDto(
            items = listOf(
                SimpleRomDto(
                    id = 100, platformId = 12, fsName = "Game (USA).sfc",
                    fsNameNoExt = "Game (USA)", fsExtension = "sfc", fsSizeBytes = 1048576,
                    name = "Game", regions = listOf("USA"), pathCoverLarge = "assets/x.png",
                    files = listOf(RomFileDto(id = 1, fileName = "Game (USA).sfc", fileSizeBytes = 1048576)),
                )
            ),
            total = 1, limit = 100, offset = 0,
        )
        val lib = LiveRommLibrary(client, platformMap)
        val page = lib.games(RommPlatform(12, "snes", "SNES", "SNES", 1), page = 0)
        assertEquals(1, page.items.size)
        assertEquals("Game", page.items.first().name)
        assertEquals("Game (USA).sfc", page.items.first().fsName)
        assertEquals(1, page.items.first().files.size)
    }

    @Test fun `games translates page number into offset`() = runTest {
        val client = mockk<RommClient>()
        every { client.getRoms(12, 100, 200, null) } returns RomsPageDto(
            items = emptyList(), total = 0, limit = 100, offset = 200,
        )
        val lib = LiveRommLibrary(client, platformMap)
        lib.games(RommPlatform(12, "snes", "SNES", "SNES", 0), page = 2)
        verify { client.getRoms(12, 100, 200, null) }
    }

    @Test fun `games passes search term through to client`() = runTest {
        val client = mockk<RommClient>()
        every { client.getRoms(12, 100, 0, "mario") } returns RomsPageDto(
            items = emptyList(), total = 0, limit = 100, offset = 0,
        )
        val lib = LiveRommLibrary(client, platformMap)
        lib.games(RommPlatform(12, "snes", "SNES", "SNES", 0), page = 0, search = "mario")
        verify { client.getRoms(12, 100, 0, "mario") }
    }

    @Test fun `game name falls back to fs name when name is blank`() = runTest {
        val client = mockk<RommClient>()
        every { client.getRoms(12, 100, 0, null) } returns RomsPageDto(
            items = listOf(
                SimpleRomDto(
                    id = 1, platformId = 12, fsName = "Untitled.sfc",
                    fsNameNoExt = "Untitled", fsExtension = "sfc", name = "",
                ),
            ),
            total = 1, limit = 100, offset = 0,
        )
        val lib = LiveRommLibrary(client, platformMap)
        val game = lib.games(RommPlatform(12, "snes", "SNES", "SNES", 1), page = 0).items.first()
        assertEquals("Untitled", game.name)
    }

    @Test fun `no cover when RomM has not stored one`() = runTest {
        val client = mockk<RommClient>()
        every { client.getRoms(12, 100, 0, null) } returns RomsPageDto(
            items = listOf(
                SimpleRomDto(id = 1, platformId = 12, fsName = "G.sfc", name = "G", pathCoverLarge = null),
            ),
            total = 1, limit = 100, offset = 0,
        )
        val lib = LiveRommLibrary(client, platformMap)
        val game = lib.games(RommPlatform(12, "snes", "SNES", "SNES", 1), page = 0).items.first()
        assertNull(game.coverPath)
    }
}
