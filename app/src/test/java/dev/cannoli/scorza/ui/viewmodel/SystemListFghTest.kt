package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SystemListFghTest {

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() {
        created.forEach { it.close() }
        created.clear()
        Dispatchers.resetMain()
    }

    private val created = mutableListOf<SystemListViewModel>()

    private val zelda = Rom(id = 1, path = File("/cannoli/Roms/GB/zelda.gb"), platformTag = "GB", displayName = "Zelda")
    private val tetris = Rom(id = 2, path = File("/cannoli/Roms/GB/tetris.gb"), platformTag = "GB", displayName = "Tetris")

    private fun viewModel(
        ports: Int,
        tools: Int,
        members: List<Rom> = listOf(zelda, tetris),
        platformOrder: List<String> = emptyList(),
    ): SystemListViewModel {
        val roms = mockk<RomsRepository>(relaxed = true)
        every { roms.platformCounts() } returns emptyMap()
        every { roms.knownPlatformTags() } returns platformOrder
        members.forEach { rom -> every { roms.gameById(rom.id) } returns rom }

        val apps = mockk<AppsRepository>(relaxed = true)
        every { apps.count(AppType.PORT) } returns ports
        every { apps.count(AppType.TOOL) } returns tools

        val collections = mockk<CollectionsRepository>(relaxed = true)
        every { collections.byId(FAVORITES_ID) } returns
            CollectionsRepository.CollectionRow(FAVORITES_ID, "Favorites", null, 0, CollectionType.FAVORITES)
        every { collections.favoritesId() } returns FAVORITES_ID
        every { collections.romIdsIn(FAVORITES_ID) } returns members.map { it.id }
        every { collections.appIdsIn(FAVORITES_ID) } returns emptyList()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.romDir } returns File("/nonexistent")

        val scheduler = mockk<ScanScheduler>(relaxed = true)
        every { scheduler.results } returns MutableSharedFlow()

        return SystemListViewModel(
            romsRepository = roms,
            romScanner = mockk(relaxed = true),
            appsRepository = apps,
            collectionsRepository = collections,
            recentlyPlayedRepository = mockk(relaxed = true),
            platformConfig = mockk<PlatformConfig>(relaxed = true),
            cannoliPaths = paths,
            romDirectoryWatcher = mockk(relaxed = true),
            scanScheduler = scheduler,
        ).also { created.add(it) }
    }

    private fun items(vm: SystemListViewModel, config: SystemListViewModel.ScanConfig): List<ListItem> {
        val done = CountDownLatch(1)
        vm.scan(config, scanDisk = false, onReady = { done.countDown() })
        assertTrue("scan did not finish", done.await(10, TimeUnit.SECONDS))
        return vm.state.value.items
    }

    private fun fgh(show: Boolean) = SystemListViewModel.ScanConfig(
        contentMode = ContentMode.FIVE_GAME_HANDHELD,
        fghCollectionId = FAVORITES_ID,
        fghShowPortsAndTools = show,
    )

    private val games = listOf(
        ListItem.GameItem(dev.cannoli.scorza.model.ListItem.RomItem(zelda)),
        ListItem.GameItem(dev.cannoli.scorza.model.ListItem.RomItem(tetris)),
    )

    @Test fun `with the setting off the list is only the collection`() {
        assertEquals(games, items(viewModel(ports = 2, tools = 3), fgh(show = false)))
    }

    @Test fun `with the setting on the folders follow the games`() {
        assertEquals(
            games + ListItem.PortsFolder("Ports", 2) + ListItem.ToolsFolder("Tools", 3),
            items(viewModel(ports = 2, tools = 3), fgh(show = true)),
        )
    }

    @Test fun `an empty folder is left out`() {
        assertEquals(games + ListItem.ToolsFolder("Tools", 3), items(viewModel(ports = 0, tools = 3), fgh(show = true)))
        assertEquals(games + ListItem.PortsFolder("Ports", 2), items(viewModel(ports = 2, tools = 0), fgh(show = true)))
    }

    @Test fun `the folders keep the order the other modes give them`() {
        val order = listOf(SystemListViewModel.TAG_TOOLS, SystemListViewModel.TAG_PORTS)
        val platforms = items(
            viewModel(ports = 2, tools = 3, platformOrder = order),
            SystemListViewModel.ScanConfig(showRecentlyPlayed = false, showFavorites = false),
        )
        val handheld = items(viewModel(ports = 2, tools = 3, platformOrder = order), fgh(show = true))

        assertEquals(listOf(ListItem.ToolsFolder("Tools", 3), ListItem.PortsFolder("Ports", 2)), platforms)
        assertEquals(games + platforms, handheld)
    }

    @Test fun `an empty favorites collection is an empty list`() {
        assertEquals(emptyList<ListItem>(), items(viewModel(ports = 0, tools = 0, members = emptyList()), fgh(show = false)))
    }

    @Test fun `folders cannot be picked up for reordering in five game handheld`() {
        val vm = viewModel(ports = 2, tools = 3)
        val list = items(vm, fgh(show = true))
        vm.setSelectedIndex(list.indexOfFirst { it is ListItem.PortsFolder })
        vm.enterReorderMode()
        assertEquals(false, vm.isReorderMode())
    }

    private companion object {
        const val FAVORITES_ID = 7L
    }
}
