package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            CannoliPaths(root.absolutePath).gameOverrideCfg("GBA", "Game", launchCore),
            "input_max_users = \"3\"",
        )
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("3", cfg["input_max_users"])
    }

    // The point of core-keying: tuning done under one core must not follow the game onto another.
    @Test fun `an override written for a different core is not applied`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.gameOverrideCfg("GBA", "Game", "some_other_core"), "input_max_users = \"3\"")
        write(paths.systemOverrideCfg("GBA", "some_other_core"), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertNotEquals("3", cfg["input_max_users"])
        assertNotEquals("true", cfg["rewind_enable"])
    }

    @Test fun `a game override outranks the system override for the same core`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideCfg("GBA", launchCore), "rewind_enable = \"false\"")
        write(paths.gameOverrideCfg("GBA", "Game", launchCore), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["rewind_enable"])
    }

    // Core options live in their own file; the launch config only points RetroArch at it.
    @Test fun `core options are composed from the tiers with the game winning`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideOpt("GBA", launchCore), "mgba_gb_colors = \"grey\"\nmgba_idle_opt = \"remove\"")
        write(paths.gameOverrideOpt("GBA", "Game", launchCore), "mgba_gb_colors = \"DMG\"")

        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertEquals(paths.coreOptionsLaunchOpt.absolutePath, cfg["core_options_path"])
        assertEquals("true", cfg["global_core_options"])
        val opts = paths.coreOptionsLaunchOpt.readLines()
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.take(it).trim() to l.drop(it + 1).trim().trim('"') } }
            .toMap()
        assertEquals("DMG", opts["mgba_gb_colors"])
        assertEquals("remove", opts["mgba_idle_opt"])
    }

    // A key dropped from a tier must stop applying, not survive in the file RetroArch flushed last.
    @Test fun `the composed core options file is rewritten whole each launch`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.coreOptionsLaunchOpt, "stale_key = \"1\"")
        write(paths.systemOverrideOpt("GBA", launchCore), "mgba_idle_opt = \"remove\"")

        launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        val text = paths.coreOptionsLaunchOpt.readText()
        assertTrue(text.contains("mgba_idle_opt"))
        assertFalse(text.contains("stale_key"))
    }

    // Options tuned under one core must not reach another, same as the cfg tiers.
    @Test fun `core options written for a different core are not composed in`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideOpt("GBA", "some_other_core"), "mgba_idle_opt = \"remove\"")

        launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertFalse(paths.coreOptionsLaunchOpt.readText().contains("mgba_idle_opt"))
    }

    // Screen visibility rides RetroArch's own settings_show_ flags rather than a refusal list in
    // the menu. A typo would be silent: the flag would do nothing and the screen would appear. This
    // checks each one against the census of what RetroArch actually registers.
    @Test fun `every settings_show flag written is a setting RetroArch registers`() {
        val census = javaClass.classLoader!!.getResourceAsStream("ra-settings-census.tsv")!!
            .bufferedReader().readLines().drop(1)
            .mapNotNull { it.split("\t").firstOrNull() }.toSet()

        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        val written = cfg.keys.filter { it.startsWith("settings_show_") }

        assertTrue("no settings_show flags were written at all", written.isNotEmpty())
        val unknown = written.filterNot { it in census }
        assertTrue(
            "these are written to hide a screen but RetroArch registers no such setting, so the " +
                "screen is not actually hidden:\n" + unknown.joinToString("\n") { "  $it" },
            unknown.isEmpty(),
        )
    }

    @Test fun `a custom cfg key overrides the same key set in a system override`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideCfg("GBA", launchCore), "rewind_enable = \"true\"")
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
