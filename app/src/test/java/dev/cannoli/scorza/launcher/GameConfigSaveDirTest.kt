package dev.cannoli.scorza.launcher

import io.mockk.every
import org.junit.Assert.assertEquals
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
