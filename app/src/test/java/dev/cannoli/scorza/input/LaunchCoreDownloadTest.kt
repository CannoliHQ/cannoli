package dev.cannoli.scorza.input

import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A migrated mapping names a core the embedded runner has never downloaded. Reporting it as missing
 * there would read as the migration having lost the choice, when the choice is intact and only the
 * file is absent, so it is fetched instead and the launch runs when it lands.
 */
class LaunchCoreDownloadTest {

    private val launchManager = mockk<dev.cannoli.scorza.launcher.LaunchManager>(relaxed = true)
    private val installer = mockk<CoreInstaller>(relaxed = true)
    private val coreInfo = mockk<dev.cannoli.scorza.config.CoreInfoRepository>(relaxed = true)
    private val saveSync = mockk<dev.cannoli.scorza.romm.sync.SaveSyncService>(relaxed = true)
    private val pathsProvider = mockk<dev.cannoli.scorza.di.CannoliPathsProvider>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private val rom = Rom(
        id = 1L,
        path = File("/tmp/roms/SNES/Mario.sfc"),
        platformTag = "SNES",
        displayName = "Mario",
    )

    /** Relaxed except the four the assertions touch: naming all twenty would test wiring. */
    private fun actions() = LauncherActions(
        context = context,
        ioScope = CoroutineScope(Dispatchers.Unconfined),
        settings = mockk(relaxed = true),
        collectionsRepository = mockk(relaxed = true),
        recentlyPlayedRepository = mockk(relaxed = true),
        romsRepository = mockk(relaxed = true),
        appsRepository = mockk(relaxed = true),
        launchManager = launchManager,
        platformConfig = mockk(relaxed = true),
        gameOverrideStore = mockk(relaxed = true),
        artworkLookup = mockk(relaxed = true),
        arcadeTitleLookup = mockk(relaxed = true),
        nav = mockk(relaxed = true),
        systemListViewModel = mockk(relaxed = true),
        gameListViewModel = mockk(relaxed = true),
        settingsViewModel = mockk(relaxed = true),
        saveSyncService = saveSync,
        pathsProvider = pathsProvider,
        coreInstaller = installer,
        coreInfo = coreInfo,
    )

    private fun setUpCommon() {
        every { context.packageName } returns "dev.cannoli.scorza"
        every { pathsProvider.romDir } returns File("/tmp/roms")
        // Not a save-synced game, so the launch runs straight through rather than via the sync path.
        every { saveSync.isSyncableGame(any()) } returns null
    }

    private fun launch() = actions().launchSelected(ListItem.RomItem(rom), resume = false)

    private fun missing(coreId: String, name: String) =
        DialogState.MissingCore(name, platformTag = "SNES", coreId = coreId)

    @Test fun `a missing but downloadable core downloads instead of reporting missing`() {
        setUpCommon()
        every { coreInfo.runsOnThisDevice("snes9x_libretro") } returns true
        every { launchManager.launchRom(rom) } returns missing("snes9x_libretro", "Snes9x")

        val result = launch()

        assertTrue("expected Launching, got $result", result is DialogState.Launching)
        verify { installer.downloadCore(any(), "snes9x_libretro", "Snes9x", any()) }
    }

    @Test fun `the launch is retried once the core lands`() {
        setUpCommon()
        every { coreInfo.runsOnThisDevice("snes9x_libretro") } returns true
        every { launchManager.launchRom(rom) } returns missing("snes9x_libretro", "Snes9x")
        val onInstalled = slot<() -> Unit>()
        every { installer.downloadCore(any(), any(), any(), capture(onInstalled)) } answers {}

        launch()
        every { launchManager.launchRom(rom) } returns null
        onInstalled.captured.invoke()

        verify(exactly = 2) { launchManager.launchRom(rom) }
    }

    // A core with no Android build can never arrive, so the dialog is the honest answer.
    @Test fun `a core with no Android build still reports missing`() {
        setUpCommon()
        every { coreInfo.runsOnThisDevice("mupen64plus_next_libretro") } returns false
        every { launchManager.launchRom(rom) } returns
            missing("mupen64plus_next_libretro", "Mupen64Plus-Next")

        val result = launch()

        assertTrue("expected MissingCore, got $result", result is DialogState.MissingCore)
        verify(exactly = 0) { installer.downloadCore(any(), any(), any(), any()) }
    }

    // Guards the default: a MissingCore raised without an id must not be treated as downloadable,
    // or an empty id reaches the downloader and fetches nothing forever.
    @Test fun `a MissingCore with no core id reports missing`() {
        setUpCommon()
        every { launchManager.launchRom(rom) } returns
            DialogState.MissingCore("Snes9x", platformTag = "SNES")

        val result = launch()

        assertTrue(result is DialogState.MissingCore)
        verify(exactly = 0) { installer.downloadCore(any(), any(), any(), any()) }
    }
}
