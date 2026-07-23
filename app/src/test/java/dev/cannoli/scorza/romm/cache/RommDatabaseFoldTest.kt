package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommSearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDatabaseFoldTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        val dbFile = File(tmp.newFolder("Config"), "romm.db")
        db = RommDatabase { dbFile }
    }

    @After fun tearDown() = db.close()

    private fun game(
        id: Int,
        platformId: Int,
        name: String,
        fsName: String,
        groupKey: Int = id,
        isMainSibling: Boolean = false,
        regions: List<String> = emptyList(),
    ) = RommGame(
        id, platformId, name, fsName, 0L, summary = null, revision = null,
        regions = regions, languages = emptyList(), coverPath = null, files = emptyList(),
        groupKey = groupKey, isMainSibling = isMainSibling,
    )

    @Test fun `two siblings fold to one folded game with the main sibling as representative`() {
        db.upsertGames(listOf(
            GameRecord(game(10, 1, "Zelda (USA)", "zelda_usa.sfc", groupKey = 10, isMainSibling = true), null),
            GameRecord(game(11, 1, "Zelda (Japan)", "zelda_jpn.sfc", groupKey = 10, isMainSibling = false), null),
        ))
        val folded = db.foldedGames(platformId = 1, search = null)
        assertEquals(1, folded.size)
        val f = folded.single()
        assertEquals(2, f.variantCount)
        assertEquals(10, f.game.id)
        assertEquals(setOf(10, 11), f.memberIds.toSet())
        assertEquals(setOf("zelda_usa.sfc", "zelda_jpn.sfc"), f.memberFsNames.toSet())
    }

    @Test fun `with no main sibling representative is lowest region rank then sort key`() {
        db.upsertGames(listOf(
            GameRecord(game(20, 1, "Metroid (Japan)", "metroid_jpn.sfc", groupKey = 20, regions = listOf("Japan")), null),
            GameRecord(game(21, 1, "Metroid (Europe)", "metroid_eu.sfc", groupKey = 20, regions = listOf("Europe")), null),
            GameRecord(game(22, 1, "Metroid (USA)", "metroid_usa.sfc", groupKey = 20, regions = listOf("USA")), null),
        ))
        val f = db.foldedGames(1, null).single()
        assertEquals(3, f.variantCount)
        assertEquals(22, f.game.id)
        assertEquals("USA", f.game.regions.single())
    }

    @Test fun `singletons each yield their own folded game`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Alpha", "alpha.sfc"), null),
            GameRecord(game(2, 1, "Beta", "beta.sfc"), null),
        ))
        val folded = db.foldedGames(1, null)
        assertEquals(listOf("Alpha", "Beta"), folded.map { it.game.name })
        assertEquals(listOf(1, 1), folded.map { it.variantCount })
        assertEquals(listOf(listOf(1), listOf(2)), folded.map { it.memberIds })
    }

    @Test fun `folded games are ordered by sort key`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Game 10", "g10.sfc"), null),
            GameRecord(game(2, 1, "Game 2", "g2.sfc"), null),
            GameRecord(game(3, 1, "Game 1", "g1.sfc"), null),
        ))
        assertEquals(listOf("Game 1", "Game 2", "Game 10"), db.foldedGames(1, null).map { it.game.name })
    }

    @Test fun `folded games search filters by name`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Super Mario World", "smw.sfc"), null),
            GameRecord(game(2, 1, "Donkey Kong", "dk.sfc"), null),
        ))
        assertEquals(listOf("Super Mario World"), db.foldedGames(1, "mario").map { it.game.name })
    }

    @Test fun `games on different platforms never merge`() {
        db.upsertGames(listOf(
            GameRecord(game(1, 1, "Contra", "contra_snes.sfc", groupKey = 1), null),
            GameRecord(game(2, 2, "Contra", "contra_gba.gba", groupKey = 2), null),
        ))
        val results = db.foldedGlobalSearch(RommSearchQuery("contra"))
        assertEquals(2, results.size)
        assertEquals(listOf(1, 1), results.map { it.variantCount })
        assertEquals(setOf(1, 2), results.map { it.game.id }.toSet())
    }

    @Test fun `global search folds siblings on the same platform`() {
        db.upsertGames(listOf(
            GameRecord(game(30, 1, "Sonic (USA)", "sonic_usa.md", groupKey = 30, regions = listOf("USA")), null),
            GameRecord(game(31, 1, "Sonic (Japan)", "sonic_jpn.md", groupKey = 30, regions = listOf("Japan")), null),
        ))
        val results = db.foldedGlobalSearch(RommSearchQuery("sonic"))
        assertEquals(1, results.size)
        assertEquals(30, results.single().game.id)
        assertEquals(2, results.single().variantCount)
    }

    @Test fun `folded collection query folds siblings and preserves members`() {
        db.upsertPlatforms(listOf(
            dev.cannoli.scorza.romm.RommPlatform(1, "snes", "SNES", "Super Nintendo", 0) to null,
        ))
        db.upsertGames(listOf(
            GameRecord(game(40, 1, "Chrono (USA)", "chrono_usa.sfc", groupKey = 40, isMainSibling = true), null),
            GameRecord(game(41, 1, "Chrono (Japan)", "chrono_jpn.sfc", groupKey = 40), null),
            GameRecord(game(42, 1, "Standalone", "standalone.sfc"), null),
        ))
        db.upsertCollections(listOf(RommCollection("c1", RommCollectionGroup.USER, "Faves", 3) to null))
        db.setCollectionMembers("c1", listOf(40, 41, 42))

        val folded = db.foldedGamesForCollection("c1", null)
        assertEquals(2, folded.size)
        val chrono = folded.first { it.game.id == 40 }
        assertEquals(2, chrono.variantCount)
        assertEquals(setOf(40, 41), chrono.memberIds.toSet())
        val standalone = folded.first { it.game.id == 42 }
        assertEquals(1, standalone.variantCount)
    }

    @Test fun `groupMembers returns all members in representative order`() {
        db.upsertGames(listOf(
            GameRecord(game(51, 1, "Kirby (Japan)", "kirby_jpn.sfc", groupKey = 50, regions = listOf("Japan")), null),
            GameRecord(game(52, 1, "Kirby (Europe)", "kirby_eu.sfc", groupKey = 50, regions = listOf("Europe")), null),
            GameRecord(game(50, 1, "Kirby (USA)", "kirby_usa.sfc", groupKey = 50, isMainSibling = true, regions = listOf("USA")), null),
        ))
        val members = db.groupMembers(50)
        assertEquals(listOf(50, 52, 51), members.map { it.id })
    }

    @Test fun `region rank is persisted and drives order USA over Europe over Japan`() {
        db.upsertGames(listOf(
            GameRecord(game(61, 1, "Pilot (Europe)", "pilot_eu.sfc", groupKey = 60, regions = listOf("Europe")), null),
            GameRecord(game(62, 1, "Pilot (Japan)", "pilot_jpn.sfc", groupKey = 60, regions = listOf("Japan")), null),
            GameRecord(game(60, 1, "Pilot (World)", "pilot_world.sfc", groupKey = 60, regions = listOf("World")), null),
        ))
        val members = db.groupMembers(60)
        assertEquals(listOf(60, 61, 62), members.map { it.id })
        assertEquals(60, db.foldedGames(1, null).single().game.id)
    }
}
