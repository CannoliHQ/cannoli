package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDatabaseCollectionsTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        val dbFile = File(tmp.newFolder("Config"), "romm.db")
        db = RommDatabase { dbFile }
    }

    @After fun tearDown() = db.close()

    private fun platform(id: Int, tag: String, name: String) =
        RommPlatform(id = id, slug = tag.lowercase(), cannoliTag = tag, displayName = name, romCount = 0)

    private fun game(id: Int, platformId: Int, name: String, fsName: String) =
        RommGame(id, platformId, name, fsName, 0L, summary = null, revision = null,
            regions = emptyList(), languages = emptyList(), coverPath = null, files = emptyList())

    @Test fun `collections returns only enabled groups with present members`() {
        db.upsertPlatforms(listOf(
            platform(1, "SNES", "Super Nintendo") to null,
            platform(2, "GBA", "Game Boy Advance") to null,
        ))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Final Fantasy VI", "ff6.sfc"), null)))

        db.upsertCollections(listOf(
            RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null,
            RommCollection("genre:rpg", RommCollectionGroup.VIRTUAL, "RPGs", 1) to null,
        ))
        db.setCollectionMembers("1", listOf(10))
        db.setCollectionMembers("genre:rpg", listOf(10))

        val userOnly = db.collections(setOf(RommCollectionGroup.USER))
        assertEquals(listOf("1"), userOnly.map { it.id })

        val games = db.gamesForCollection("1", null, 50, 0)
        assertEquals(listOf(10), games.map { it.id })
    }

    @Test fun `collections excludes disabled group`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Game", "g.sfc"), null)))

        db.upsertCollections(listOf(
            RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null,
            RommCollection("genre:rpg", RommCollectionGroup.VIRTUAL, "RPGs", 1) to null,
        ))
        db.setCollectionMembers("1", listOf(10))
        db.setCollectionMembers("genre:rpg", listOf(10))

        val virtualOnly = db.collections(setOf(RommCollectionGroup.VIRTUAL))
        assertEquals(listOf("genre:rpg"), virtualOnly.map { it.id })

        val empty = db.collections(emptySet())
        assertEquals(emptyList<RommCollection>(), empty)
    }

    @Test fun `gamesForCollection excludes non-members`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(
            GameRecord(game(10, 1, "Alpha", "alpha.sfc"), null),
            GameRecord(game(11, 1, "Beta", "beta.sfc"), null),
        ))
        db.upsertCollections(listOf(RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null))
        db.setCollectionMembers("1", listOf(10))

        val games = db.gamesForCollection("1", null, 50, 0)
        assertEquals(listOf(10), games.map { it.id })
    }

    @Test fun `gamesForCollectionCount and search filter work`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(
            GameRecord(game(10, 1, "Super Mario World", "smw.sfc"), null),
            GameRecord(game(11, 1, "Donkey Kong", "dk.sfc"), null),
        ))
        db.upsertCollections(listOf(RommCollection("1", RommCollectionGroup.USER, "All", 2) to null))
        db.setCollectionMembers("1", listOf(10, 11))

        assertEquals(2, db.gamesForCollectionCount("1", null))
        assertEquals(1, db.gamesForCollectionCount("1", "mario"))
        assertEquals(listOf(10), db.gamesForCollection("1", "mario", 50, 0).map { it.id })
    }

    @Test fun `allCollectionIds and deleteCollections work`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Game", "g.sfc"), null)))
        db.upsertCollections(listOf(
            RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null,
            RommCollection("genre:rpg", RommCollectionGroup.VIRTUAL, "RPGs", 1) to null,
        ))
        db.setCollectionMembers("1", listOf(10))
        db.setCollectionMembers("genre:rpg", listOf(10))

        assertEquals(setOf("1", "genre:rpg"), db.allCollectionIds())

        db.deleteCollections(setOf("genre:rpg"))
        assertEquals(setOf("1"), db.allCollectionIds())
        assertEquals(0, db.gamesForCollectionCount("genre:rpg", null))
    }

    @Test fun `clearAll removes collections and collection_roms`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Game", "g.sfc"), null)))
        db.upsertCollections(listOf(RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null))
        db.setCollectionMembers("1", listOf(10))

        db.clearAll()

        assertEquals(emptySet<String>(), db.allCollectionIds())
        assertEquals(0, db.gamesForCollectionCount("1", null))
    }

    @Test fun `setCollectionMembers replaces existing members`() {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(
            GameRecord(game(10, 1, "Alpha", "alpha.sfc"), null),
            GameRecord(game(11, 1, "Beta", "beta.sfc"), null),
        ))
        db.upsertCollections(listOf(RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null))
        db.setCollectionMembers("1", listOf(10, 11))
        assertEquals(2, db.gamesForCollectionCount("1", null))

        db.setCollectionMembers("1", listOf(11))
        assertEquals(1, db.gamesForCollectionCount("1", null))
        assertEquals(listOf(11), db.gamesForCollection("1", null, 50, 0).map { it.id })
    }
}
