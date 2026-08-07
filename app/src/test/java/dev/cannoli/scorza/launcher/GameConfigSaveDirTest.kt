package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The per-game config names the save directory outright instead of letting RetroArch derive it.
 * Deriving it means by-content sorting, which appends the ROM's *parent* directory: correct for a
 * loose ROM, wrong for a bundled multi-disc game whose parent is the bundle folder.
 */
class GameConfigSaveDirTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)
    private val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        every { platformConfig.getCoreName(any()) } returns "mgba_libretro"
        // Explicitly on the embedded runner, which is the path that writes a Cannoli-authored
        // config. An external RetroArch is launched by intent and never gets one.
        every { platformConfig.getPlatformChoice(any()) } returns
            dev.cannoli.scorza.config.EmulatorChoice(
                dev.cannoli.scorza.config.EmulatorSource.Embedded, "mgba_libretro",
            )
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns null
        val activeMappingHolder = mockk<ActiveMappingHolder>(relaxed = true)
        every { activeMappingHolder.active } returns MutableStateFlow<DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            emuLauncher = mockk(relaxed = true),
            apkLauncher = mockk(relaxed = true),
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = activeMappingHolder,
            atomicRename = mockk(relaxed = true),
            installedCoreService = null,
            gameOverrides = gameOverrides,
        )
    }

    private fun launchedConfig(root: File, rom: Rom, mgr: LaunchManager = manager(root)): Map<String, String> {
        val dialog = mgr.launchRom(rom)
        val cfg = CannoliPaths(root.absolutePath).raLaunchCfg
        if (!cfg.exists()) throw AssertionError("no launch config written, launch returned $dialog")
        return cfg.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i < 0) null
                else line.take(i).trim() to line.drop(i + 1).trim().trim('"')
            }.toMap()
    }

    private fun rom(root: File, relPath: String, tag: String): Rom {
        val file = File(root, relPath).apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = file, platformTag = tag, displayName = "Game")
    }

    @Test fun `a loose ROM saves into its platform directory`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(File(root, "Saves/GBA").absolutePath, cfg["savefile_directory"])
    }

    // The regression this exists for. By-content sorting would resolve the parent of the .cue,
    // putting saves in Saves/Game where neither the launcher nor save sync looks for them.
    @Test fun `a bundled multi-disc game saves into its platform directory, not the bundle folder`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/PSX/Game/disc1.cue", "PSX"))
        assertEquals(File(root, "Saves/PSX").absolutePath, cfg["savefile_directory"])
    }

    // Pinning the directory only holds if the sorting that would append to it is off. Leaving
    // either flag on reintroduces a level below the pinned path.
    @Test fun `both savefile sort flags are off once the directory is pinned`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["sort_savefiles_enable"])
        assertEquals("false", cfg["sort_savefiles_by_content_enable"])
    }

    @Test fun `save states stay pinned per game with sorting off`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(File(root, "Save States/GBA/Game").absolutePath, cfg["savestate_directory"])
        assertEquals("false", cfg["sort_savestates_enable"])
        assertEquals("false", cfg["sort_savestates_by_content_enable"])
    }

    // The base config is written once and only when it is absent, so an install made before the
    // key existed would never see it. Only the per-launch overlay reaches every user.
    @Test fun `every launch pins the Cannoli autoconfig directory`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(
            File(root, "Config/Input/Autoconfig").absolutePath,
            cfg["joypad_autoconfig_dir"],
        )
    }

    // A platform nobody has mapped resolves to the embedded runner, so it still gets a
    // Cannoli-authored config rather than falling through to an external RetroArch.
    @Test fun `an unmapped platform still launches on the embedded runner`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getPlatformChoice(any()) } returns null
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"), mgr)
        assertEquals(File(root, "Saves/GBA").absolutePath, cfg["savefile_directory"])
    }
}
