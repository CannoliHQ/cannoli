package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommGroup
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPage
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RommBrowseViewModelGlobalSearchTest {

    private fun game(id: Int, platformId: Int, name: String, fsName: String) =
        RommGame(id, platformId, name, fsName, sizeBytes = 0, summary = null, revision = null,
            regions = emptyList(), languages = emptyList(), coverPath = null, files = emptyList())

    private val library = object : RommLibrary {
        override suspend fun platforms() = listOf(
            RommPlatform(id = 1, slug = "snes", cannoliTag = "SNES", displayName = "Super Nintendo", romCount = 0),
            RommPlatform(id = 2, slug = "gba", cannoliTag = "GBA", displayName = "Game Boy Advance", romCount = 0),
        )
        override suspend fun games(platform: RommPlatform, page: Int, search: String?) =
            RommPage<RommGame>(emptyList(), 0, 100, 0)
        override suspend fun foldedGames(platform: RommPlatform, search: String?) = emptyList<RommGroup>()
        override suspend fun searchAll(query: RommSearchQuery) = listOf(
            game(1, 1, "Super Mario World", "smw.sfc"),
            game(2, 2, "Mario Kart", "mk.gba"),
        )
        override suspend fun collections(groups: Set<RommCollectionGroup>, virtualType: String?) = emptyList<RommCollection>()
        override suspend fun collectionGroupCounts() = emptyMap<RommCollectionGroup, Int>()
        override suspend fun virtualTypeCounts() = emptyMap<String, Int>()
    }

    @Test fun `global search returns natural-sorted rows with remote state`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = library,
            syncCoordinator = null,
            db = null,
            presentNamesFor = { emptySet() },
            linkedIdsProvider = { emptySet() },
        )
        vm.loadPlatforms()
        vm.loadGlobalSearch(RommSearchQuery("mario"))
        val rows = vm.searchResults.value!!.rows
        assertEquals(listOf("Mario Kart", "Super Mario World"), rows.map { it.game.name })
        assertEquals(listOf(LocalState.REMOTE, LocalState.REMOTE), rows.map { it.localState })
    }

    @Test fun `global search marks linked games present`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = library,
            syncCoordinator = null,
            db = null,
            presentNamesFor = { emptySet() },
            linkedIdsProvider = { setOf(1) },
        )
        vm.loadPlatforms()
        vm.loadGlobalSearch(RommSearchQuery("mario"))
        val present = vm.searchResults.value!!.rows.first { it.game.id == 1 }.localState
        assertEquals(LocalState.PRESENT, present)
    }
}
