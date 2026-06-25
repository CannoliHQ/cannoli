package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPage
import dev.cannoli.scorza.romm.RommPlatform
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RommBrowseViewModelTest {

    private val platform = RommPlatform(12, "snes", "SNES", "Super Nintendo", 2)
    private fun game(id: Int, name: String, fs: String, size: Long) =
        RommGame(id, 12, name, fs, size, null, null, emptyList(), emptyList(), null, emptyList())

    private fun vm(lib: RommLibrary) = RommBrowseViewModel(
        library = lib,
        syncCoordinator = null,
        db = null,
        presentNamesFor = { setOf("mario.sfc") },
        linkedIdsProvider = { emptySet() },
    )

    @Test fun `loadPlatforms publishes mapped platforms`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.platforms() } returns listOf(platform)
        val vm = vm(lib)
        vm.loadPlatforms()
        assertEquals(listOf("Super Nintendo"), vm.platforms.value.map { it.displayName })
    }

    @Test fun `enterBrowse orders platforms alphabetically by display name`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.platforms() } returns listOf(
            RommPlatform(1, "gba", "GBA", "Game Boy Advance", 1),
            RommPlatform(2, "snes", "SNES", "Super Nintendo", 1),
            RommPlatform(3, "psx", "PSX", "PlayStation", 1),
        )
        coEvery { lib.collections(any()) } returns emptyList()
        val vm = vm(lib)
        vm.enterBrowse()
        assertEquals(listOf("GBA", "PSX", "SNES"), vm.platforms.value.map { it.cannoliTag })
    }

    @Test fun `openPlatform loads games sorted with local state`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.games(platform, 0, null) } returns RommPage(
            items = listOf(game(2, "Zelda", "Zelda.sfc", 1L), game(1, "Mario", "Mario.sfc", 100L)),
            total = 2, limit = 100, offset = 0,
        )
        val vm = vm(lib)
        vm.openPlatform(platform)
        val games = vm.games.value
        assertEquals(listOf("Mario", "Zelda"), games.map { it.game.name })
        assertEquals(LocalState.PRESENT, games.first { it.game.name == "Mario" }.localState)
        assertEquals(LocalState.REMOTE, games.first { it.game.name == "Zelda" }.localState)
    }

    @Test fun `openPlatform with a search term queries it`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.games(platform, 0, "mario") } returns RommPage(
            items = listOf(game(1, "Mario", "Mario.sfc", 100L)), total = 1, limit = 100, offset = 0,
        )
        val vm = vm(lib)
        vm.openPlatform(platform, "mario")
        assertEquals(listOf("Mario"), vm.games.value.map { it.game.name })
    }

    private suspend fun loadedVm(): RommBrowseViewModel {
        val lib = mockk<RommLibrary>()
        coEvery { lib.games(platform, 0, null) } returns RommPage(
            items = listOf(game(2, "Zelda", "Zelda.sfc", 1L), game(1, "Mario", "Mario.sfc", 100L)),
            total = 2, limit = 100, offset = 0,
        )
        return vm(lib).also { it.openPlatform(platform) }
    }

    @Test fun `refreshLocalState re-derives present state when the rom is indexed`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.games(platform, 0, null) } returns RommPage(
            items = listOf(game(2, "Zelda", "Zelda.sfc", 1L)), total = 1, limit = 100, offset = 0,
        )
        val present = mutableSetOf<String>()
        val vm = RommBrowseViewModel(
            library = lib,
            syncCoordinator = null,
            db = null,
            presentNamesFor = { present.toSet() },
            linkedIdsProvider = { emptySet() },
        )
        vm.openPlatform(platform)
        assertEquals(LocalState.REMOTE, vm.games.value.single().localState)
        present.add("zelda.sfc")
        vm.refreshLocalState()
        assertEquals(LocalState.PRESENT, vm.games.value.single().localState)
    }

    @Test fun `enterMultiSelect pre-checks a remote row`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(2)
        assertEquals(true, vm.isMultiSelect())
        assertEquals(setOf(2), vm.checkedIds.value)
    }

    @Test fun `enterMultiSelect does not pre-check a present row`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(1)
        assertEquals(true, vm.isMultiSelect())
        assertEquals(emptySet<Int>(), vm.checkedIds.value)
    }

    @Test fun `toggleChecked adds and removes remote rows`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(null)
        vm.toggleChecked(2)
        assertEquals(setOf(2), vm.checkedIds.value)
        vm.toggleChecked(2)
        assertEquals(emptySet<Int>(), vm.checkedIds.value)
    }

    @Test fun `toggleChecked ignores present rows`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(null)
        vm.toggleChecked(1)
        assertEquals(emptySet<Int>(), vm.checkedIds.value)
    }

    @Test fun `confirmMultiSelect returns checked games and clears state`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(2)
        val games = vm.confirmMultiSelect()
        assertEquals(listOf(2), games.map { it.id })
        assertEquals(false, vm.isMultiSelect())
        assertEquals(emptySet<Int>(), vm.checkedIds.value)
    }

    @Test fun `cancelMultiSelect clears state`() = runTest {
        val vm = loadedVm()
        vm.enterMultiSelect(2)
        vm.cancelMultiSelect()
        assertEquals(false, vm.isMultiSelect())
        assertEquals(emptySet<Int>(), vm.checkedIds.value)
    }
}
