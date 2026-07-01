package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import dev.cannoli.scorza.romm.fakeFold
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RommBrowseViewModelTest {

    private val platform = RommPlatform(12, "snes", "SNES", "Super Nintendo", 2)
    private fun game(id: Int, name: String, fs: String, size: Long) =
        RommGame(id, 12, name, fs, size, null, null, emptyList(), emptyList(), null, emptyList(), groupKey = id)

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
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(
            listOf(game(2, "Zelda", "Zelda.sfc", 1L), game(1, "Mario", "Mario.sfc", 100L)),
        )
        val vm = vm(lib)
        vm.openPlatform(platform)
        val games = vm.games.value!!.rows
        assertEquals(listOf("Mario", "Zelda"), games.map { it.game.name })
        assertEquals(LocalState.PRESENT, games.first { it.game.name == "Mario" }.localState)
        assertEquals(LocalState.REMOTE, games.first { it.game.name == "Zelda" }.localState)
    }

    @Test fun `openPlatform with a search term queries it`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.foldedGames(platform, "mario") } returns fakeFold(
            listOf(game(1, "Mario", "Mario.sfc", 100L)),
        )
        val vm = vm(lib)
        vm.openPlatform(platform, "mario")
        assertEquals(listOf("Mario"), vm.games.value!!.rows.map { it.game.name })
    }

    @Test fun `openPlatform publishes the platform id with its rows`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(
            listOf(game(1, "Mario", "Mario.sfc", 100L)),
        )
        val vm = vm(lib)
        vm.openPlatform(platform)
        assertEquals(platform.id, vm.games.value?.id)
        assertEquals(listOf("Mario"), vm.games.value!!.rows.map { it.game.name })
    }

    @Test fun `openPlatform folds siblings and marks the group present via a downloaded member`() = runTest {
        val lib = mockk<RommLibrary>()
        // Representative is the USA sibling; the present filename belongs to the Japan sibling.
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(listOf(
            game(1, "Zelda", "zelda-usa.sfc", 1L).copy(groupKey = 1, regions = listOf("USA")),
            game(2, "Zelda", "zelda-jp.sfc", 1L).copy(groupKey = 1, regions = listOf("Japan")),
        ))
        val vm = RommBrowseViewModel(
            library = lib,
            syncCoordinator = null,
            db = null,
            presentNamesFor = { setOf("zelda-jp.sfc") },
            linkedIdsProvider = { emptySet() },
        )
        vm.openPlatform(platform)
        val row = vm.games.value!!.rows.single()
        assertEquals(1, row.game.id)
        assertEquals(2, row.versionCount)
        assertEquals(LocalState.REMOTE, row.localState)
        assertEquals(true, row.anyPresent)
    }

    @Test fun `openPlatform resolves local-state lookups once per page, not per row`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(
            (1..50).map { game(it, "G$it", "g$it.sfc", 1L) },
        )
        var presentCalls = 0
        var linkedCalls = 0
        val vm = RommBrowseViewModel(
            library = lib,
            syncCoordinator = null,
            db = null,
            presentNamesFor = { presentCalls++; emptySet() },
            linkedIdsProvider = { linkedCalls++; emptySet() },
        )
        vm.openPlatform(platform)
        assertEquals(50, vm.games.value!!.rows.size)
        assertEquals(1, presentCalls)
        assertEquals(1, linkedCalls)
    }

    private suspend fun loadedVm(): RommBrowseViewModel {
        val lib = mockk<RommLibrary>()
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(
            listOf(game(2, "Zelda", "Zelda.sfc", 1L), game(1, "Mario", "Mario.sfc", 100L)),
        )
        return vm(lib).also { it.openPlatform(platform) }
    }

    @Test fun `refreshLocalState re-derives present state when the rom is indexed`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.foldedGames(platform, null) } returns fakeFold(
            listOf(game(2, "Zelda", "Zelda.sfc", 1L)),
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
        assertEquals(LocalState.REMOTE, vm.games.value!!.rows.single().localState)
        present.add("zelda.sfc")
        vm.refreshLocalState()
        assertEquals(LocalState.PRESENT, vm.games.value!!.rows.single().localState)
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

    private val snes = RommPlatform(12, "snes", "SNES", "Super Nintendo", 4)
    private val gba = RommPlatform(20, "gba", "GBA", "Game Boy Advance", 2)

    private fun searchGame(id: Int, platformId: Int, name: String, fs: String, groupKey: Int) =
        RommGame(id, platformId, name, fs, 0L, null, null, emptyList(), emptyList(), null, emptyList(), groupKey = groupKey)

    private fun searchVm(lib: RommLibrary, present: (String) -> Set<String>) = RommBrowseViewModel(
        library = lib,
        syncCoordinator = null,
        db = null,
        presentNamesFor = present,
        linkedIdsProvider = { emptySet() },
    )

    @Test fun `loadGlobalSearch folds siblings into one row carrying version count`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.platforms() } returns listOf(snes, gba)
        coEvery { lib.foldedGlobalSearch(any()) } returns fakeFold(listOf(
            searchGame(1, 12, "Zelda", "zelda-usa.sfc", groupKey = 1),
            searchGame(2, 12, "Zelda", "zelda-jp.sfc", groupKey = 1),
            searchGame(9, 20, "Metroid", "metroid.gba", groupKey = 9),
        ))
        val vm = searchVm(lib) { emptySet() }
        vm.loadGlobalSearch(RommSearchQuery("z"))
        val rows = vm.searchResults.value!!.rows
        assertEquals(listOf("Metroid", "Zelda"), rows.map { it.game.name })
        val zelda = rows.first { it.game.name == "Zelda" }
        assertEquals(2, zelda.versionCount)
        assertEquals(1, zelda.groupKey)
    }

    @Test fun `loadGlobalSearch resolves present per member platform`() = runTest {
        val lib = mockk<RommLibrary>()
        coEvery { lib.platforms() } returns listOf(snes, gba)
        coEvery { lib.foldedGlobalSearch(any()) } returns fakeFold(listOf(
            searchGame(1, 12, "Zelda", "zelda.sfc", groupKey = 1),
            searchGame(9, 20, "Metroid", "metroid.gba", groupKey = 9),
        ))
        val vm = searchVm(lib) { tag -> if (tag == "GBA") setOf("metroid.gba") else emptySet() }
        vm.loadGlobalSearch(RommSearchQuery("m"))
        val rows = vm.searchResults.value!!.rows
        assertEquals(LocalState.REMOTE, rows.first { it.game.name == "Zelda" }.localState)
        assertEquals(LocalState.PRESENT, rows.first { it.game.name == "Metroid" }.localState)
        assertEquals(true, rows.first { it.game.name == "Metroid" }.anyPresent)
    }
}
