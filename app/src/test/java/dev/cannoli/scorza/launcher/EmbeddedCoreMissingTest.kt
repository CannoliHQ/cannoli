package dev.cannoli.scorza.launcher

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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The embedded runner had no core-presence check before launch: only a separately installed
 * RetroArch was ever asked whether it held the core. So a mapping naming a core that is not in
 * filesDir/cores launched RetroArch anyway and failed inside it, with nothing said in the launcher.
 *
 * That gap becomes load-bearing once mappings migrate off external RetroArch, since a migrated
 * choice names a core the embedded runner has very likely never downloaded.
 */
class EmbeddedCoreMissingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val installedCoreService = mockk<InstalledCoreService>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)
    private val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)

    private fun rom(root: File): Rom {
        val f = File(root, "roms/SNES/Mario.sfc").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = f, platformTag = "SNES", displayName = "Mario")
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        every { installedCoreService.cacheReady } returns true
        every { platformConfig.getPlatformChoice("SNES") } returns
            EmulatorChoice(EmulatorSource.Embedded, "snes9x_libretro")
        every { platformConfig.getCoreName("SNES") } returns "snes9x_libretro"
        every { platformConfig.getCoreDisplayName("snes9x_libretro") } returns "Snes9x"
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

    @Test fun `an embedded core that is not present reports missing instead of launching`() {
        val root = tmp.newFolder()
        every { installedCoreService.embeddedCores() } returns emptySet()

        val dialog = manager(root).launchRom(rom(root)) as DialogState.MissingCore

        assertEquals("Snes9x", dialog.coreName)
        assertEquals("the id is what a download needs", "snes9x_libretro", dialog.coreId)
        assertEquals("SNES", dialog.platformTag)
        verify(exactly = 0) { retroArchLauncher.launchRicotta(any(), any(), any(), any()) }
    }

    @Test fun `an embedded core that is present launches`() {
        val root = tmp.newFolder()
        every { installedCoreService.embeddedCores() } returns setOf("snes9x_libretro")
        every { retroArchLauncher.launchRicotta(any(), any(), any(), any()) } returns LaunchResult.Success

        manager(root).launchRom(rom(root))

        verify { retroArchLauncher.launchRicotta(any(), "snes9x_libretro", any(), any()) }
    }
}
