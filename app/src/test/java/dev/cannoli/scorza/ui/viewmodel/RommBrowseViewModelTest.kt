package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalFile
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
        localFilesFor = { listOf(LocalFile("Mario.sfc", 100L)) },
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
}
