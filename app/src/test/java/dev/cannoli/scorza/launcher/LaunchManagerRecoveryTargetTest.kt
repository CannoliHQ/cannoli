package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.GameCoreOverride
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

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/GC/Mario.iso").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = romFile, platformTag = "GC", displayName = "Mario")
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { settings.retroArchPackage } returns RA
        every { platformConfig.getGameOverride(any()) } returns null
        every { installedCoreService.cacheReady } returns true
        every { installedCoreService.unresponsivePackages } returns emptySet()
        every { installedCoreService.hasCoreInPackage(any(), any()) } returns false
        val activeMappingHolder = mockk<ActiveMappingHolder>(relaxed = true)
        every { activeMappingHolder.active } returns MutableStateFlow<DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = mockk(relaxed = true),
            emuLauncher = mockk(relaxed = true),
            apkLauncher = apkLauncher,
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = activeMappingHolder,
            atomicRename = mockk(relaxed = true),
            installedCoreService = installedCoreService,
        )
    }

    @Test fun `a per game app override that is not installed points recovery at the game`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { platformConfig.getGameOverride(gc.path.absolutePath) } returns
            GameCoreOverride(appPackage = MISSING)
        every { platformConfig.getAppConfig("GC", MISSING) } returns AppConfig(MISSING)
        every { apkLauncher.launchWithRom(any(), any(), any()) } returns
            LaunchResult.AppNotInstalled(MISSING)

        val dialog = mgr.launchRom(gc) as DialogState.MissingApp

        assertEquals(gc.path.absolutePath, dialog.gamePath)
    }

    @Test fun `a platform level app that is not installed points recovery at the platform`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { platformConfig.getRunnerPreference("GC") } returns "Standalone"
        every { platformConfig.getUserAppMapping("GC") } returns MISSING
        every { platformConfig.getAppConfig("GC", MISSING) } returns AppConfig(MISSING)
        every { apkLauncher.launchWithRom(any(), any(), any()) } returns
            LaunchResult.AppNotInstalled(MISSING)

        val dialog = mgr.launchRom(gc) as DialogState.MissingApp

        assertNull(dialog.gamePath)
        assertEquals("GC", dialog.platformTag)
    }

    @Test fun `a per game core override whose core is missing points recovery at the game`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = rom(root)
        every { platformConfig.getGameOverride(gc.path.absolutePath) } returns
            GameCoreOverride(coreId = "dolphin_libretro", runner = "RetroArch")

        val dialog = mgr.launchRom(gc) as DialogState.MissingCore

        assertEquals(gc.path.absolutePath, dialog.gamePath)
        assertEquals("GC", dialog.platformTag)
        // Pins the core-install check as the branch under test, not the generic "unknown" fallback.
        assertEquals(InstalledCoreService.getPackageLabel(RA), dialog.packageLabel)
    }

    @Test fun `a platform level core that is missing points recovery at the platform`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getRunnerPreference("GC") } returns "RetroArch"
        every { platformConfig.getCoreName("GC") } returns "dolphin_libretro"

        val dialog = mgr.launchRom(rom(root)) as DialogState.MissingCore

        assertNull(dialog.gamePath)
        assertEquals("GC", dialog.platformTag)
        assertEquals(InstalledCoreService.getPackageLabel(RA), dialog.packageLabel)
    }

    companion object {
        private const val MISSING = "org.dolphinemu.mmjr"
        private const val RA = "com.retroarch"
    }
}
