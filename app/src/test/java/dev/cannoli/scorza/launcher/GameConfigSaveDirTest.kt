package dev.cannoli.scorza.launcher

import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class GameConfigSaveDirTest : LaunchConfigHarness() {

    @Test fun `a loose ROM saves into its own folder under its platform`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(File(root, "Saves/GBA/Game").absolutePath, cfg["savefile_directory"])
    }

    @Test fun `a bundled multi-disc game saves under its platform, not the bundle folder`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/PSX/Game/disc1.cue", "PSX"))
        assertEquals(File(root, "Saves/PSX/disc1").absolutePath, cfg["savefile_directory"])
        assertNotEquals(File(root, "Saves/Game").absolutePath, cfg["savefile_directory"])
    }

    @Test fun `both savefile sort flags are off once the directory is pinned`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["sort_savefiles_enable"])
        assertEquals("false", cfg["sort_savefiles_by_content_enable"])
    }

    @Test fun `save states are pinned per game and per core, with sorting off`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(
            File(root, "Save States/GBA/Game/mgba_libretro").absolutePath,
            cfg["savestate_directory"],
        )
        assertEquals("false", cfg["sort_savestates_enable"])
        assertEquals("false", cfg["sort_savestates_by_content_enable"])
    }

    @Test fun `a different core for the same game is a different directory`() {
        val root = tmp.newFolder()
        val default = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        launchCore = "gpsp_libretro"
        val other = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertNotEquals(default["savestate_directory"], other["savestate_directory"])
    }

    @Test fun `every launch pins the Cannoli autoconfig directory`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(
            File(root, "Config/Input/Autoconfig").absolutePath,
            cfg["joypad_autoconfig_dir"],
        )
    }

    @Test fun `an unmapped platform still launches on the embedded runner`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getPlatformChoice(any()) } returns null
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"), mgr)
        assertEquals(File(root, "Saves/GBA/Game").absolutePath, cfg["savefile_directory"])
    }
}
