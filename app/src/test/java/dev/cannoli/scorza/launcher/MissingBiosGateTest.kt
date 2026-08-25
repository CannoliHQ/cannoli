package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.FirmwareEntry
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A required BIOS that is absent used to reach the emulator, which then sat on it. FBNeo draws its
 * own error screen and, with no content loaded, RetroArch's menu becomes reachable behind it, so the
 * user ends up somewhere Cannoli never intended. The launch is stopped here instead.
 *
 * Only firmware the platform genuinely requires blocks a launch: optional entries are ignored, which
 * matters because FBNeo marks all 23 of its own entries optional.
 */
class MissingBiosGateTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val installedCoreService = mockk<InstalledCoreService>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)
    private val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)

    private fun rom(root: File): Rom {
        val f = File(root, "roms/NEOGEO/mslug.zip").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = f, platformTag = "NEOGEO", displayName = "Metal Slug")
    }

    private fun manager(root: File, firmware: List<Pair<FirmwareEntry, Boolean>>): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        every { platformConfig.getPlatformChoice("NEOGEO") } returns
            EmulatorChoice(EmulatorSource.Embedded, "fbneo_libretro")
        every { platformConfig.getCoreName("NEOGEO") } returns "fbneo_libretro"
        every { platformConfig.getCoreDisplayName("fbneo_libretro") } returns "FinalBurn Neo"
        every { platformConfig.getDisplayName("NEOGEO") } returns "Neo Geo"
        every { platformConfig.getFirmwareStatus("NEOGEO", "fbneo_libretro", any()) } returns firmware
        every { installedCoreService.embeddedCores() } returns setOf("fbneo_libretro")
        every { retroArchLauncher.launchRicotta(any(), any(), any(), any()) } returns LaunchResult.Success
        val holder = mockk<ActiveMappingHolder>(relaxed = true)
        every { holder.active } returns MutableStateFlow<DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            emuLauncher = mockk(relaxed = true),
            apkLauncher = mockk(relaxed = true),
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = holder,
            atomicRename = mockk(relaxed = true),
            installedCoreService = installedCoreService,
            gameOverrides = gameOverrides,
        )
    }

    private fun entry(path: String, optional: Boolean) = FirmwareEntry(path, path, optional)

    @Test fun `a required BIOS that is absent stops the launch and names the file`() {
        val root = tmp.newFolder()
        val fw = listOf(entry("fbneo/neogeo.zip", optional = false) to false)

        val dialog = manager(root, fw).launchRom(rom(root)) as DialogState.MissingBios

        assertEquals("Neo Geo", dialog.platformName)
        assertEquals("the base name is what the user puts in the folder", listOf("neogeo.zip"), dialog.files)
        assertEquals("NEOGEO", dialog.platformTag)
        verify(exactly = 0) { retroArchLauncher.launchRicotta(any(), any(), any(), any()) }
    }

    @Test fun `an optional BIOS that is absent does not stop the launch`() {
        val root = tmp.newFolder()
        val fw = listOf(entry("fbneo/neocdz.zip", optional = true) to false)

        manager(root, fw).launchRom(rom(root))

        verify { retroArchLauncher.launchRicotta(any(), "fbneo_libretro", any(), any()) }
    }

    @Test fun `a required BIOS that is present does not stop the launch`() {
        val root = tmp.newFolder()
        val fw = listOf(entry("fbneo/neogeo.zip", optional = false) to true)

        manager(root, fw).launchRom(rom(root))

        verify { retroArchLauncher.launchRicotta(any(), "fbneo_libretro", any(), any()) }
    }

    @Test fun `every absent required file is named, not just the first`() {
        val root = tmp.newFolder()
        val fw = listOf(
            entry("fbneo/neogeo.zip", optional = false) to false,
            entry("aes.zip", optional = false) to false,
            entry("fbneo/neocdz.zip", optional = true) to false,
        )

        val dialog = manager(root, fw).launchRom(rom(root)) as DialogState.MissingBios

        assertEquals(listOf("neogeo.zip", "aes.zip"), dialog.files)
    }
}
