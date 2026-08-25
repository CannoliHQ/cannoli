package dev.cannoli.scorza.launcher

import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * The per-game config names the save directory outright instead of letting RetroArch derive it.
 * Deriving it means by-content sorting, which appends the ROM's *parent* directory: correct for a
 * loose ROM, wrong for a bundled multi-disc game whose parent is the bundle folder.
 */
class GameConfigSaveDirTest : LaunchConfigHarness() {

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

    /**
     * States are keyed by core as well as by game. A state is only loadable by the core that wrote
     * it and nothing in the file says who that was, so sharing one directory across a platform's
     * cores is what handed a mupen64plus-next state to a sibling core and crashed it.
     */
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

    // Two cores on one platform must not share a directory, which is the whole point of the key.
    @Test fun `a different core for the same game is a different directory`() {
        val root = tmp.newFolder()
        val default = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        launchCore = "vbam_libretro"
        val other = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertNotEquals(default["savestate_directory"], other["savestate_directory"])
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
