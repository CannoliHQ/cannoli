package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPlatform
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CachedRommLibraryCollectionsTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: RommDatabase
    private lateinit var library: RommLibrary

    @Before fun setUp() {
        db = RommDatabase { File(tmp.newFolder("Config"), "romm.db") }
        library = CachedRommLibrary(db)
    }

    @After fun tearDown() = db.close()

    private fun platform(id: Int, tag: String, name: String) =
        RommPlatform(id = id, slug = tag.lowercase(), cannoliTag = tag, displayName = name, romCount = 0)

    private fun game(id: Int, platformId: Int, name: String, fsName: String) =
        RommGame(id, platformId, name, fsName, 0L, summary = null, revision = null,
            regions = emptyList(), languages = emptyList(), coverPath = null, files = emptyList())

    @Test fun `collections returns seeded user collection`() = runBlocking {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Final Fantasy VI", "ff6.sfc"), null)))
        db.upsertCollections(listOf(
            RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null,
            RommCollection("genre:rpg", RommCollectionGroup.VIRTUAL, "RPGs", 1) to null,
        ))
        db.setCollectionMembers("1", listOf(10))
        db.setCollectionMembers("genre:rpg", listOf(10))

        val result = library.collections(setOf(RommCollectionGroup.USER))

        assertEquals(listOf("1"), result.map { it.id })
        assertEquals(listOf("Faves"), result.map { it.name })
        assertEquals(listOf(RommCollectionGroup.USER), result.map { it.group })
    }

    @Test fun `collections returns empty for excluded group`() = runBlocking {
        db.upsertPlatforms(listOf(platform(1, "SNES", "Super Nintendo") to null))
        db.upsertGames(listOf(GameRecord(game(10, 1, "Game", "g.sfc"), null)))
        db.upsertCollections(listOf(
            RommCollection("1", RommCollectionGroup.USER, "Faves", 1) to null,
        ))
        db.setCollectionMembers("1", listOf(10))

        val result = library.collections(emptySet())

        assertEquals(emptyList<RommCollection>(), result)
    }
}
