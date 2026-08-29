package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.RomDirectoryWatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which platforms a disk scan visits. A platform that still holds rows but has lost its folder is
 *  only reachable through the reconcile, and only [RomScanner.scanPlatform] clears it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemListScanTargetsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() {
        created.forEach { it.close() }
        created.clear()
        Dispatchers.resetMain()
    }

    private lateinit var romScanner: RomScanner
    private lateinit var romsRepository: RomsRepository
    private val created = mutableListOf<SystemListViewModel>()

    private fun viewModel(romDir: File, indexed: Map<String, Int>): SystemListViewModel {
        romScanner = mockk(relaxed = true)
        romsRepository = mockk(relaxed = true)
        every { romsRepository.platformCounts() } returns indexed
        every { romsRepository.knownPlatformTags() } returns indexed.keys.toList()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.romDir } returns romDir

        val platformConfig = mockk<PlatformConfig>(relaxed = true)
        every { platformConfig.isKnownTag(any()) } answers { firstArg<String>() in KNOWN }
        every { platformConfig.isArcade(any()) } returns false
        every { platformConfig.resolvePlatform(any(), any(), any()) } answers {
            val tag = firstArg<String>()
            Platform(tag = tag, displayName = tag, coreName = null, gameCount = thirdArg())
        }

        val scheduler = mockk<ScanScheduler>(relaxed = true)
        every { scheduler.results } returns MutableSharedFlow()

        return SystemListViewModel(
            romsRepository = romsRepository,
            romScanner = romScanner,
            appsRepository = mockk(relaxed = true),
            collectionsRepository = mockk(relaxed = true),
            recentlyPlayedRepository = mockk(relaxed = true),
            platformConfig = platformConfig,
            cannoliPaths = paths,
            romDirectoryWatcher = mockk<RomDirectoryWatcher>(relaxed = true),
            scanScheduler = scheduler,
        ).also { created.add(it) }
    }

    /** The tags handed to the scanner, in call order. */
    private fun scannedTags(vm: SystemListViewModel, reconcileOrphans: Boolean): List<String> {
        val tags = mutableListOf<String>()
        val tag = slot<String>()
        every { romScanner.scanPlatform(capture(tag), any()) } answers {
            tags.add(tag.captured)
            RomScanner.SyncCounts(0, 0, 0)
        }
        val done = CountDownLatch(1)
        vm.scan(reconcileOrphans = reconcileOrphans, onReady = { done.countDown() })
        assertTrue("scan did not finish", done.await(10, TimeUnit.SECONDS))
        return tags
    }

    @Test fun `reconcile visits a platform that lost its folder`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(listOf("GB", "SNES"), scanned.sorted())
    }

    @Test fun `an ordinary scan leaves an orphaned platform alone`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = false)

        assertEquals(listOf("GB"), scanned)
    }

    @Test fun `a missing rom directory clears nothing`() {
        val romDir = File(tmp.root, "NotMounted")

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(emptyList<String>(), scanned)
    }

    @Test fun `declining the reconcile leaves the orphaned rows in place`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1, "SNES" to 4))
        assertEquals(listOf("GB"), scannedTags(vm, reconcileOrphans = false))
        assertEquals(listOf("GB", "SNES"), scannedTags(vm, reconcileOrphans = true).sorted())
    }

    @Test fun `a folder that is not a platform is never scanned`() {
        val romDir = tmp.newFolder("Roms")
        File(romDir, "GB").mkdirs()
        File(romDir, "#recycle").mkdirs()

        val vm = viewModel(romDir, indexed = mapOf("GB" to 1))
        val scanned = scannedTags(vm, reconcileOrphans = true)

        assertEquals(listOf("GB"), scanned)
    }

    private companion object {
        val KNOWN = setOf("GB", "SNES", "NES")
    }
}
