package dev.cannoli.scorza.romm

import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RommBrowseViewModelMatchTest {

    private val snes = RommPlatform(1, "snes", "SNES", "Super Nintendo", 2)

    private fun game(id: Int, fsName: String, size: Long) =
        RommGame(id, 1, "Game $id", fsName, size, null, null, emptyList(), emptyList(), null, emptyList(), groupKey = id)

    private class FakeLibrary(private val games: List<RommGame>) : RommLibrary {
        override suspend fun platforms() = listOf(RommPlatform(1, "snes", "SNES", "Super Nintendo", 2))
        override suspend fun games(platform: RommPlatform, page: Int, search: String?) =
            RommPage(if (page == 0) games else emptyList(), games.size, RommLibrary.PAGE_SIZE, 0)
        override suspend fun foldedGames(platform: RommPlatform, search: String?) = fakeFold(games)
        override suspend fun foldedGamesForCollection(collectionId: String, search: String?) = emptyList<RommFoldedGame>()
        override suspend fun foldedGlobalSearch(query: RommSearchQuery) = emptyList<RommFoldedGame>()
        override suspend fun groupMembers(groupKey: Int) = games.filter { it.groupKey == groupKey }
        override suspend fun searchAll(query: RommSearchQuery) = emptyList<RommGame>()
        override suspend fun collections(groups: Set<RommCollectionGroup>, virtualType: String?) = emptyList<RommCollection>()
        override suspend fun collectionGroupCounts() = emptyMap<RommCollectionGroup, Int>()
        override suspend fun virtualTypeCounts() = emptyMap<String, Int>()
    }

    @Test fun `a linked id is PRESENT even when filename does not match`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = FakeLibrary(listOf(game(10, "renamed.sfc", 999L))),
            syncCoordinator = null,
            db = null,
            presentNamesFor = { emptySet() },
            linkedIdsProvider = { setOf(10) },
        )
        vm.openPlatform(snes)
        assertEquals(LocalState.PRESENT, vm.games.value!!.rows.single().localState)
    }

    @Test fun `falls back to a filename and platform match when not linked`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = FakeLibrary(listOf(game(10, "a.sfc", 100L), game(11, "b.sfc", 200L))),
            syncCoordinator = null,
            db = null,
            presentNamesFor = { setOf("a.sfc") },
            linkedIdsProvider = { emptySet() },
        )
        vm.openPlatform(snes)
        val byId = vm.games.value!!.rows.associate { it.game.id to it.localState }
        assertEquals(LocalState.PRESENT, byId[10])
        assertEquals(LocalState.REMOTE, byId[11])
    }

    // The picker (InputRouter.onNorth, Android-only) fetches members via groupMembers and marks
    // present ones via presentIdsForTag; assert the reachable VM pieces build the right data.
    @Test fun `groupMembers plus presentIdsForTag drive the variant picker data`() = runBlocking {
        val usa = game(10, "zelda-usa.sfc", 1L).copy(name = "Zelda", groupKey = 10, regions = listOf("USA"))
        val jpn = game(11, "zelda-jp.sfc", 1L).copy(name = "Zelda", groupKey = 10, regions = listOf("Japan"))
        val vm = RommBrowseViewModel(
            library = FakeLibrary(listOf(usa, jpn)),
            syncCoordinator = null,
            db = null,
            presentNamesFor = { setOf("zelda-jp.sfc") },
            linkedIdsProvider = { emptySet() },
        )
        val members = vm.groupMembers(10)
        assertEquals(setOf(10, 11), members.map { it.id }.toSet())
        val present = vm.presentIdsForTag(snes.cannoliTag, members)
        assertEquals(setOf(11), present)
    }

    private val tecmoBase = RommFile("Tecmo Super Bowl (USA).nes", 1, null, null, null, id = 1832, category = "game", isTopLevel = true)
    private val tecmoHack = RommFile("Tecmo Super Bowl 2025.nes", 1, null, null, null, id = 1833, subDir = "hacks", category = "hack")
    private val tecmo = game(1343, "Tecmo Super Bowl", 2L).copy(files = listOf(tecmoBase, tecmoHack))

    private fun vmWith(games: List<RommGame>, present: Set<String>) = RommBrowseViewModel(
        library = FakeLibrary(games),
        syncCoordinator = null,
        db = null,
        presentNamesFor = { present },
        linkedIdsProvider = { emptySet() },
    )

    @Test fun `an unlinked local base game file makes its hacks entry present`() = runBlocking {
        val vm = vmWith(listOf(tecmo), setOf("tecmo super bowl (usa).nes"))
        vm.openPlatform(snes)
        val row = vm.games.value!!.rows.single()
        assertEquals(LocalState.PRESENT, row.localState)
        assertEquals(true, row.anyPresent)
        assertEquals(setOf(1343), vm.presentIdsForTag("SNES", listOf(tecmo)))
    }

    @Test fun `a local hack alone does not make its entry present`() = runBlocking {
        val vm = vmWith(listOf(tecmo), setOf("tecmo super bowl 2025.nes"))
        vm.openPlatform(snes)
        val row = vm.games.value!!.rows.single()
        assertEquals(LocalState.REMOTE, row.localState)
        assertEquals(false, row.anyPresent)
        assertEquals(emptySet<Int>(), vm.presentIdsForTag("SNES", listOf(tecmo)))
    }

    @Test fun `a folded group is any-present through a sibling's base game file`() = runBlocking {
        val main = game(20, "Other (USA).nes", 1L).copy(groupKey = 20, isMainSibling = true)
        val sib = tecmo.copy(groupKey = 20)
        val vm = vmWith(listOf(main, sib), setOf("tecmo super bowl (usa).nes"))
        vm.openPlatform(snes)
        val row = vm.games.value!!.rows.single()
        assertEquals(LocalState.REMOTE, row.localState)
        assertEquals(true, row.anyPresent)
    }
}
