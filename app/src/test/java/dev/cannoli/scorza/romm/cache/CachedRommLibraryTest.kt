package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPlatform
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CachedRommLibraryTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: RommDatabase
    private lateinit var library: RommLibrary

    @Before fun setUp() {
        db = RommDatabase { File(tmp.newFolder("Config"), "romm.db") }
        library = CachedRommLibrary(db)
    }

    @After fun tearDown() = db.close()

    private val snes = RommPlatform(1, "snes", "SNES", "Super Nintendo", 150)

    private fun seed(count: Int) {
        db.replacePlatforms(listOf(snes to null))
        db.upsertGames((1..count).map {
            GameRecord(RommGame(it, 1, "Game %03d".format(it), "g$it.sfc", 0, null, null, emptyList(), emptyList(), null, emptyList()), null)
        })
    }

    @Test fun `platforms come from the mirror`() = runBlocking {
        seed(1)
        assertEquals(listOf("Super Nintendo"), library.platforms().map { it.displayName })
    }

    @Test fun `games paginate with hasMore`() = runBlocking {
        seed(RommLibrary.PAGE_SIZE + 10)
        val page0 = library.games(snes, page = 0)
        assertEquals(RommLibrary.PAGE_SIZE, page0.items.size)
        assertTrue(page0.hasMore)
        val page1 = library.games(snes, page = 1)
        assertEquals(10, page1.items.size)
        assertFalse(page1.hasMore)
    }

    @Test fun `search narrows results`() = runBlocking {
        db.replacePlatforms(listOf(snes to null))
        db.upsertGames(listOf(
            GameRecord(RommGame(1, 1, "Super Mario World", "smw.sfc", 0, null, null, emptyList(), emptyList(), null, emptyList()), null),
            GameRecord(RommGame(2, 1, "F-Zero", "fz.sfc", 0, null, null, emptyList(), emptyList(), null, emptyList()), null),
        ))
        assertEquals(listOf("Super Mario World"), library.games(snes, page = 0, search = "mario").items.map { it.name })
    }
}
