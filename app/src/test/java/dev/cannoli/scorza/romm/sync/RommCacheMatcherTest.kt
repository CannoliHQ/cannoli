package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.cache.GameRecord
import dev.cannoli.scorza.romm.cache.RommDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommCacheMatcherTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: RommDatabase

    @Before fun setUp() {
        val dbFile = File(tmp.newFolder("Config"), "romm.db")
        db = RommDatabase { dbFile }
        db.replacePlatforms(listOf(RommPlatform(1, "nes", "NES", "NES", 0) to null))
    }

    @After fun tearDown() = db.close()

    private fun game(id: Int, fsName: String, files: List<RommFile> = emptyList()) =
        RommGame(id, 1, "Game $id", fsName, 0L, null, null, emptyList(), emptyList(), null, files)

    private val tecmo = game(
        1343, "Tecmo Super Bowl",
        listOf(
            RommFile("Tecmo Super Bowl (USA).nes", 1, null, null, null, id = 1832, category = "game", isTopLevel = true),
            RommFile("Tecmo Super Bowl 2025.nes", 1, null, null, null, id = 1833, subDir = "hacks", category = "hack"),
        ),
    )

    private fun store(vararg games: RommGame) = db.upsertGames(games.map { GameRecord(it, null) })

    @Test fun `resolves the top-level game file of a hacks entry`() {
        store(tecmo)
        assertEquals(1343, RommCacheMatcher(db).rommIdFor("NES", "Tecmo Super Bowl (USA).nes"))
    }

    @Test fun `never resolves a hack file`() {
        store(tecmo)
        assertNull(RommCacheMatcher(db).rommIdFor("NES", "Tecmo Super Bowl 2025.nes"))
    }

    @Test fun `fs name still resolves`() {
        store(tecmo)
        assertEquals(1343, RommCacheMatcher(db).rommIdFor("NES", "tecmo super bowl"))
    }

    @Test fun `an fs name match wins over another entry's top-level game file`() {
        store(tecmo, game(9, "Tecmo Super Bowl (USA).nes"))
        assertEquals(9, RommCacheMatcher(db).rommIdFor("NES", "Tecmo Super Bowl (USA).nes"))
    }

    @Test fun `an entry with two top-level game files indexes neither`() {
        val two = game(
            5, "Pair",
            listOf(
                RommFile("A.nes", 1, null, null, null, id = 1, category = "game", isTopLevel = true),
                RommFile("B.nes", 1, null, null, null, id = 2, category = "game", isTopLevel = true),
            ),
        )
        store(two)
        assertNull(RommCacheMatcher(db).rommIdFor("NES", "A.nes"))
    }
}
