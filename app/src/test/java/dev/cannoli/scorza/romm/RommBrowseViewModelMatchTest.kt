package dev.cannoli.scorza.romm

import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RommBrowseViewModelMatchTest {

    private val snes = RommPlatform(1, "snes", "SNES", "Super Nintendo", 2)

    private fun game(id: Int, fsName: String, size: Long) =
        RommGame(id, 1, "Game $id", fsName, size, null, null, emptyList(), emptyList(), null, emptyList())

    private class FakeLibrary(private val games: List<RommGame>) : RommLibrary {
        override suspend fun platforms() = listOf(RommPlatform(1, "snes", "SNES", "Super Nintendo", 2))
        override suspend fun games(platform: RommPlatform, page: Int, search: String?) =
            RommPage(if (page == 0) games else emptyList(), games.size, RommLibrary.PAGE_SIZE, 0)
    }

    @Test fun `a linked id is PRESENT even when filename and size do not match`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = FakeLibrary(listOf(game(10, "renamed.sfc", 999L))),
            syncCoordinator = null,
            db = null,
            localFilesFor = { emptyList() },
            linkedIdsProvider = { setOf(10) },
        )
        vm.openPlatform(snes)
        assertEquals(LocalState.PRESENT, vm.games.value.single().localState)
    }

    @Test fun `falls back to filename and size when not linked`() = runBlocking {
        val vm = RommBrowseViewModel(
            library = FakeLibrary(listOf(game(10, "a.sfc", 100L), game(11, "b.sfc", 200L))),
            syncCoordinator = null,
            db = null,
            localFilesFor = { listOf(LocalFile("a.sfc", 100L)) },
            linkedIdsProvider = { emptySet() },
        )
        vm.openPlatform(snes)
        val byId = vm.games.value.associate { it.game.id to it.localState }
        assertEquals(LocalState.PRESENT, byId[10])
        assertEquals(LocalState.REMOTE, byId[11])
    }
}
