package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchManagerStandalonePickTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val apkLauncher = mockk<ApkLauncher>(relaxed = true)

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/3ds/Zelda.3ds").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = romFile, platformTag = "3DS", displayName = "Zelda")
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { platformConfig.getGameOverride(any()) } returns null
        every { platformConfig.getRunnerPreference("3DS") } returns "Standalone"
        every { apkLauncher.launchWithRom(any(), any(), any()) } returns LaunchResult.Success
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = mockk(relaxed = true),
            emuLauncher = mockk(relaxed = true),
            apkLauncher = apkLauncher,
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = mockk(relaxed = true),
        )
    }

    private fun launchedPackage(root: File): String {
        val pkg = slot<String>()
        verify { apkLauncher.launchWithRom(capture(pkg), any(), any()) }
        return pkg.captured
    }

    @Test fun `explicit standalone pick wins over the first installed app`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getUserAppMapping("3DS") } returns "com.picked.emu"
        every { platformConfig.getAppConfig("3DS", "com.picked.emu") } returns AppConfig("com.picked.emu")
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns AppConfig("org.first.listed")

        mgr.launchRom(rom(root))

        assertEquals("com.picked.emu", launchedPackage(root))
    }

    @Test fun `without a pick launch still auto routes to the first installed app`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getUserAppMapping("3DS") } returns null
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns AppConfig("org.first.listed")

        mgr.launchRom(rom(root))

        assertEquals("org.first.listed", launchedPackage(root))
    }
}
