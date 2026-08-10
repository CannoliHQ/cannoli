package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Pins the tier stack buildGameConfig composes over retroarch.cfg before the plumbing band is
 * applied: Games/<tag>/<base>.cfg and Systems/<tag>.cfg are preferences, custom.cfg is the
 * user's own escape hatch and wins among preferences, and the plumbing band still wins over all
 * of them. #36: a custom.cfg cannot smuggle a cheevos or save-dir key into the launch config.
 */
class LaunchManagerConfigTest : LaunchConfigHarness() {

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }

    @Test fun `a game override key appears in the launch config`() {
        val root = tmp.newFolder()
        write(
            CannoliPaths(root.absolutePath).gameOverrideCfg("GBA", "Game"),
            "input_max_users = \"3\"",
        )
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("3", cfg["input_max_users"])
    }

    @Test fun `a custom cfg key overrides the same key set in a system override`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideCfg("GBA"), "rewind_enable = \"true\"")
        write(paths.customCfg, "rewind_enable = \"false\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["rewind_enable"])
    }

    @Test fun `a custom cfg cannot turn hardcore on, the plumbing wins`() {
        val root = tmp.newFolder()
        write(CannoliPaths(root.absolutePath).customCfg, "cheevos_hardcore_mode_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertNotEquals("true", cfg["cheevos_hardcore_mode_enable"])
    }

    @Test fun `a custom cfg cannot redirect the save directory, the plumbing wins`() {
        val root = tmp.newFolder()
        write(CannoliPaths(root.absolutePath).customCfg, "savefile_directory = \"/tmp/attacker\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(File(root, "Saves/GBA").absolutePath, cfg["savefile_directory"])
    }

    @Test fun `auto overrides are disabled in the launch config`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["auto_overrides_enable"])
    }

    @Test fun `a malformed custom cfg line is dropped without failing the launch`() {
        val root = tmp.newFolder()
        write(
            CannoliPaths(root.absolutePath).customCfg,
            "this line has no equals sign\ninput_max_users = \"3\"",
        )
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("3", cfg["input_max_users"])
    }

    @Test fun `missing tier files contribute nothing and do not fail the launch`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals(File(root, "Saves/GBA").absolutePath, cfg["savefile_directory"])
    }
}
