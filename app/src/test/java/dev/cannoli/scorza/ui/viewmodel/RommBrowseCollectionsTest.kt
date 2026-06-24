package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPage
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import dev.cannoli.scorza.romm.cache.GameRecord
import dev.cannoli.scorza.romm.cache.RommDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommBrowseCollectionsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: RommDatabase

    private val snes = RommPlatform(1, "snes", "SNES", "Super Nintendo", 2)
    private val gba = RommPlatform(2, "gba", "GBA", "Game Boy Advance", 1)

    private fun game(id: Int, platformId: Int, name: String, fsName: String, size: Long = 0L) =
        RommGame(id, platformId, name, fsName, size, null, null, emptyList(), emptyList(), null, emptyList())

    private val collection = RommCollection("col-1", RommCollectionGroup.USER, "My Faves", 2)

    private val library = object : RommLibrary {
        override suspend fun platforms() = listOf(snes, gba)
        override suspend fun games(platform: RommPlatform, page: Int, search: String?) =
            RommPage<RommGame>(emptyList(), 0, RommLibrary.PAGE_SIZE, 0)
        override suspend fun searchAll(query: RommSearchQuery) = emptyList<RommGame>()
        override suspend fun collections(groups: Set<RommCollectionGroup>) =
            if (RommCollectionGroup.USER in groups) listOf(collection) else emptyList()
    }

    @Before fun setUp() {
        val dbFile = File(tmp.newFolder("Config"), "romm.db")
        db = RommDatabase { dbFile }
        db.upsertPlatforms(listOf(snes to null, gba to null))
        db.upsertGames(listOf(
            GameRecord(game(10, 1, "Super Mario World", "smw.sfc", 100L), null),
            GameRecord(game(20, 2, "Mario Kart", "mk.gba", 200L), null),
        ))
        db.upsertCollections(listOf(collection to null))
        db.setCollectionMembers("col-1", listOf(10, 20))
    }

    @After fun tearDown() = db.close()

    private fun vm(
        presentNamesFor: (String) -> Set<String> = { emptySet() },
        linkedIds: Set<Int> = emptySet(),
        enabledGroups: Set<RommCollectionGroup> = setOf(RommCollectionGroup.USER),
    ) = RommBrowseViewModel(
        library = library,
        syncCoordinator = null,
        db = db,
        presentNamesFor = presentNamesFor,
        linkedIdsProvider = { linkedIds },
        enabledCollectionGroups = { enabledGroups },
    )

    @Test fun `loadCollections populates collections from the library`() = runBlocking {
        val vm = vm()
        vm.loadCollections()
        assertEquals(listOf("My Faves"), vm.collections.value.map { it.name })
    }

    @Test fun `hasAnyCollections returns false before load`() {
        val vm = vm()
        assertEquals(false, vm.hasAnyCollections())
    }

    @Test fun `hasAnyCollections returns true after loadCollections`() = runBlocking {
        val vm = vm()
        vm.loadCollections()
        assertEquals(true, vm.hasAnyCollections())
    }

    @Test fun `openCollection sets loadedCollectionId`() = runBlocking {
        val vm = vm()
        vm.openCollection(collection)
        assertEquals("col-1", vm.loadedCollectionId.value)
    }

    @Test fun `openCollection resolves two games on different platforms`() = runBlocking {
        val vm = vm()
        vm.openCollection(collection)
        val rows = vm.collectionGames.value
        assertEquals(2, rows.size)
        val snesRow = rows.first { it.game.id == 10 }
        assertEquals(snes.cannoliTag, snesRow.platform.cannoliTag)
        assertEquals(snes.id, snesRow.platform.id)
        val gbaRow = rows.first { it.game.id == 20 }
        assertEquals(gba.cannoliTag, gbaRow.platform.cannoliTag)
        assertEquals(gba.id, gbaRow.platform.id)
    }

    @Test fun `openCollection marks a present game PRESENT by filename`() = runBlocking {
        val vm = vm(presentNamesFor = { tag -> if (tag == "SNES") setOf("smw.sfc") else emptySet() })
        vm.openCollection(collection)
        val rows = vm.collectionGames.value
        assertEquals(LocalState.PRESENT, rows.first { it.game.id == 10 }.localState)
        assertEquals(LocalState.REMOTE, rows.first { it.game.id == 20 }.localState)
    }

    @Test fun `openCollection marks a linked game PRESENT regardless of filename`() = runBlocking {
        val vm = vm(linkedIds = setOf(20))
        vm.openCollection(collection)
        val rows = vm.collectionGames.value
        assertEquals(LocalState.PRESENT, rows.first { it.game.id == 20 }.localState)
        assertEquals(LocalState.REMOTE, rows.first { it.game.id == 10 }.localState)
    }

    @Test fun `loadMoreCollection appends the next page`() = runBlocking {
        val thirdGame = game(30, 1, "Yoshi", "yoshi.sfc")
        db.upsertGames(listOf(GameRecord(thirdGame, null)))
        db.upsertCollections(listOf(RommCollection("col-1", RommCollectionGroup.USER, "My Faves", 3) to null))
        db.setCollectionMembers("col-1", listOf(10, 20, 30))

        val smallPage = RommBrowseViewModel(
            library = library,
            syncCoordinator = null,
            db = db,
            presentNamesFor = { emptySet() },
            linkedIdsProvider = { emptySet() },
            enabledCollectionGroups = { setOf(RommCollectionGroup.USER) },
            collectionPageSize = 2,
        )
        smallPage.openCollection(collection)
        assertEquals(2, smallPage.collectionGames.value.size)
        smallPage.loadMoreCollection()
        assertEquals(3, smallPage.collectionGames.value.size)
    }
}
