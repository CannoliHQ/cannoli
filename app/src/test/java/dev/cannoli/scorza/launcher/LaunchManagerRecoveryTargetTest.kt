package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// A failed launch has to say which mapping supplied the emulator that failed, otherwise recovery
// rewrites the platform mapping while the per-game override that broke the launch survives.
class LaunchManagerRecoveryTargetTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val apkLauncher = mockk<ApkLauncher>(relaxed = true)
    private val installedCoreService = mockk<InstalledCoreService>(relaxed = true)
    private val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/GC/Mario.iso").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = romFile, platformTag = "GC", displayName = "Mario")
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        val activeMappingHolder = mockk<ActiveMappingHolder>(relaxed = true)
        every { activeMappingHolder.active } returns MutableStateFlow<DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            apkLauncher = apkLauncher,
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = activeMappingHolder,
            portRouter = dev.cannoli.scorza.input.runtime.PortRouter(),
            installedCoreService = installedCoreService,
            gameOverrides = gameOverrides,
        )
    }

    @Test fun `a per game app override that is not installed points recovery at the game`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { gameOverrides.get(gc.id) } returns
            EmulatorChoice(EmulatorSource.Standalone, appPackage = MISSING)
        every { platformConfig.getAppConfig("GC", MISSING) } returns AppConfig(MISSING)
        every { apkLauncher.launchWithRom(any(), any(), any()) } returns
            LaunchResult.AppNotInstalled(MISSING)

        val dialog = mgr.launchRom(gc) as DialogState.MissingApp

        assertEquals(gc.id, dialog.romId)
    }

    @Test fun `a platform level app that is not installed points recovery at the platform`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { platformConfig.getPlatformChoice("GC") } returns
            dev.cannoli.scorza.config.EmulatorChoice(dev.cannoli.scorza.config.EmulatorSource.Standalone)
        every { platformConfig.getUserAppMapping("GC") } returns MISSING
        every { platformConfig.getAppConfig("GC", MISSING) } returns AppConfig(MISSING)
        every { apkLauncher.launchWithRom(any(), any(), any()) } returns
            LaunchResult.AppNotInstalled(MISSING)

        val dialog = mgr.launchRom(gc) as DialogState.MissingApp

        assertNull(dialog.romId)
        assertEquals("GC", dialog.platformTag)
    }

    @Test fun `a per game core override whose core is missing points recovery at the game`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { gameOverrides.get(gc.id) } returns
            EmulatorChoice(EmulatorSource.Embedded, coreId = "dolphin_libretro")

        val dialog = mgr.launchRom(gc) as DialogState.MissingCore

        assertEquals(gc.id, dialog.romId)
        assertEquals("GC", dialog.platformTag)
        // Pins the core-presence check as the branch under test, not the no-emulator-set fallback.
        assertEquals("dolphin_libretro", dialog.coreId)
    }

    @Test fun `a platform level core that is missing points recovery at the platform`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getPlatformChoice("GC") } returns
            dev.cannoli.scorza.config.EmulatorChoice(
                dev.cannoli.scorza.config.EmulatorSource.Embedded, "dolphin_libretro",
            )
        every { platformConfig.getCoreName("GC") } returns "dolphin_libretro"

        val dialog = mgr.launchRom(rom(root)) as DialogState.MissingCore

        assertNull(dialog.romId)
        assertEquals("GC", dialog.platformTag)
        assertEquals("dolphin_libretro", dialog.coreId)
    }

    // A standalone platform with nothing to resolve used to report a missing core named "unknown",
    // which named a core the user never chose. It is a different failure and says so.
    @Test fun `a standalone platform with no app resolved names the platform, not a core`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getPlatformChoice("GC") } returns
            EmulatorChoice(EmulatorSource.Standalone)
        every { platformConfig.getUserAppMapping("GC") } returns null
        every { platformConfig.getFirstInstalledApp("GC", any()) } returns null
        every { platformConfig.getAppPackage("GC") } returns null
        every { platformConfig.getDisplayName("GC") } returns "GameCube"

        val dialog = mgr.launchRom(rom(root)) as DialogState.NoEmulatorSet

        assertEquals("GameCube", dialog.platformName)
        assertEquals("GC", dialog.platformTag)
    }

    // Recovery still has to land somewhere, or the dialog offers a fix that goes nowhere.
    @Test fun `a per game standalone override with no app resolved points recovery at the game`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { gameOverrides.get(gc.id) } returns EmulatorChoice(EmulatorSource.Standalone)
        every { platformConfig.getUserAppMapping("GC") } returns null
        every { platformConfig.getFirstInstalledApp("GC", any()) } returns null
        every { platformConfig.getAppPackage("GC") } returns null

        val dialog = mgr.launchRom(gc) as DialogState.NoEmulatorSet

        assertEquals(gc.id, dialog.romId)
    }

    companion object {
        private const val MISSING = "org.dolphinemu.mmjr"
    }
}
