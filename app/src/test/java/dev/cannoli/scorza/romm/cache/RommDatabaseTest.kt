package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDatabaseTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dbFile: File
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        dbFile = File(tmp.newFolder("Config"), "romm.db")
        db = RommDatabase { dbFile }
    }

    @After fun tearDown() = db.close()

    private fun platform(id: Int, tag: String, name: String, count: Int) =
        RommPlatform(id = id, slug = tag.lowercase(), cannoliTag = tag, displayName = name, romCount = count)

    private fun game(id: Int, platformId: Int, name: String, fsName: String, size: Long = 0, files: List<RommFile> = emptyList()) =
        RommGame(id, platformId, name, fsName, size, summary = null, revision = null,
            regions = emptyList(), languages = emptyList(), coverPath = null, files = files)

    @Test fun `platforms round-trip and replace clears old rows`() {
        db.replacePlatforms(listOf(platform(1, "SNES", "Super Nintendo", 3) to "2024-01-01T00:00:00"))
        db.upsertGames(listOf(GameRecord(game(1, 1, "Game 1", "g1.sfc"), null)))
        assertEquals(listOf("Super Nintendo"), db.platforms().map { it.displayName })

        db.replacePlatforms(listOf(platform(2, "GBA", "Game Boy Advance", 5) to null))
        db.upsertGames(listOf(GameRecord(game(10, 2, "Game 10", "g10.gba"), null)))
        assertEquals(listOf(2), db.platforms().map { it.id })
    }

    @Test fun `games are paged and sorted naturally`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Game 10", "g10.sfc"), null),
            GameRecord(game(2, 1, "Game 2", "g2.sfc"), null),
            GameRecord(game(3, 1, "Game 1", "g1.sfc"), null),
        ))
        val names = db.games(platformId = 1, search = null, limit = 2, offset = 0).map { it.name }
        assertEquals(listOf("Game 1", "Game 2"), names)
        assertEquals(listOf("Game 10"), db.games(1, null, 2, 2).map { it.name })
        assertEquals(3, db.gamesCount(1, null))
    }

    @Test fun `search filters by name`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Super Mario World", "smw.sfc"), null),
            GameRecord(game(2, 1, "Donkey Kong", "dk.sfc"), null),
        ))
        assertEquals(listOf("Super Mario World"), db.games(1, "mario", 100, 0).map { it.name })
        assertEquals(1, db.gamesCount(1, "mario"))
    }

    @Test fun `files and metadata persist`() {
        val files = listOf(RommFile("disc1.bin", 42L, "crc1", null, null))
        db.upsertGames(listOf(GameRecord(game(7, 1, "Disc Game", "disc.cue", size = 42L, files = files), "2024-02-02T00:00:00")))
        val loaded = db.games(1, null, 100, 0).single()
        assertEquals(files, loaded.files)
        assertEquals(42L, loaded.sizeBytes)
    }

    @Test fun `metadatum fields round-trip`() {
        db.upsertGames(listOf(GameRecord(
            game(5, 1, "FF6", "ff6.sfc").copy(
                companies = listOf("Square"),
                genres = listOf("Role-playing (RPG)"),
                gameModes = listOf("Single player"),
                firstReleaseDate = 765072000000L,
            ),
            null,
        )))
        val loaded = db.games(1, null, 100, 0).single()
        assertEquals(listOf("Square"), loaded.companies)
        assertEquals(listOf("Role-playing (RPG)"), loaded.genres)
        assertEquals(listOf("Single player"), loaded.gameModes)
        assertEquals(765072000000L, loaded.firstReleaseDate)
    }

    @Test fun `upsert replaces a game by id`() {
        db.upsertGames(listOf(GameRecord(game(1, 1, "Old", "old.sfc"), null)))
        db.upsertGames(listOf(GameRecord(game(1, 1, "New", "new.sfc"), null)))
        assertEquals(listOf("New"), db.games(1, null, 100, 0).map { it.name })
    }

    @Test fun `counts by platform and delete by id`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "A", "a.sfc"), null),
            GameRecord(game(2, 1, "B", "b.sfc"), null),
            GameRecord(game(3, 2, "C", "c.gba"), null),
        ))
        assertEquals(mapOf(1 to 2, 2 to 1), db.gameCountsByPlatform())
        assertEquals(setOf(1, 2), db.cachedGameIds(1))
        db.deleteGames(setOf(2))
        assertEquals(setOf(1), db.cachedGameIds(1))
    }

    @Test fun `allGames returns all games for a platform`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Alpha", "alpha.sfc"), null),
            GameRecord(game(2, 1, "Beta", "beta.sfc"), null),
        ))
        assertEquals(setOf("alpha.sfc", "beta.sfc"), db.allGames(1).map { it.fsName }.toSet())
    }

    @Test fun `searchAllGames matches across platforms`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Super Mario World", "smw.sfc"), null),
            GameRecord(game(2, 2, "Mario Kart", "mk.gba"), null),
            GameRecord(game(3, 1, "Donkey Kong", "dk.sfc"), null),
        ))
        val names = db.searchAllGames(RommSearchQuery("mario")).map { it.name }.sorted()
        assertEquals(listOf("Mario Kart", "Super Mario World"), names)
    }

    @Test fun `searchAllGames is diacritic-insensitive`() {
        db.upsertGames(listOf(GameRecord(game(1, 1, "Pokémon", "pkmn.gba"), null)))
        assertEquals(listOf("Pokémon"), db.searchAllGames(RommSearchQuery("poke")).map { it.name })
        assertEquals(listOf("Pokémon"), db.searchAllGames(RommSearchQuery("pokemon")).map { it.name })
    }

    @Test fun `in-platform games search is diacritic-insensitive`() {
        db.upsertGames(listOf(GameRecord(game(1, 1, "Pokémon", "pkmn.gba"), null)))
        assertEquals(listOf("Pokémon"), db.games(platformId = 1, search = "poke", limit = 100, offset = 0).map { it.name })
    }

    @Test fun `searchAllGames blank term returns empty`() {
        db.upsertGames(listOf(GameRecord(game(1, 1, "Game 1", "g1.sfc"), null)))
        assertEquals(emptyList<String>(), db.searchAllGames(RommSearchQuery("  ")).map { it.name })
    }

    @Test fun `sync_state round-trips and clearAll empties everything`() {
        db.setSyncState("cursor", "2024-09-09T00:00:00")
        assertEquals("2024-09-09T00:00:00", db.getSyncState("cursor"))
        db.replacePlatforms(listOf(platform(1, "SNES", "SNES", 1) to null))
        db.upsertGames(listOf(GameRecord(game(1, 1, "A", "a.sfc"), null)))

        db.clearAll()

        assertEquals(emptyList<RommPlatform>(), db.platforms())
        assertEquals(0, db.gamesCount(1, null))
        assertNull(db.getSyncState("cursor"))
    }
}
