package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

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

    @Test fun `an override written for a different core is not applied`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.gameOverrideCfg("GBA", "Game", "some_other_core"), "input_max_users = \"3\"")
        write(paths.systemOverrideCfg("GBA", "some_other_core"), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertNotEquals("3", cfg["input_max_users"])
        assertNotEquals("true", cfg["rewind_enable"])
    }

    @Test fun `a shared system override survives a core it was not written under`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemSharedCfg("GBA"), "cannoli_overlay = \"Fancy Bezel\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("Fancy Bezel", cfg["cannoli_overlay"])
    }

    @Test fun `a shared game override survives a core it was not written under`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.gameSharedCfg("GBA", "Game"), "cannoli_overlay = \"CRT Frame\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("CRT Frame", cfg["cannoli_overlay"])
    }

    @Test fun `a shared game override outranks the shared system override`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemSharedCfg("GBA"), "cannoli_overlay = \"System Pick\"")
        write(paths.gameSharedCfg("GBA", "Game"), "cannoli_overlay = \"Game Pick\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("Game Pick", cfg["cannoli_overlay"])
    }

    @Test fun `a core-keyed system override outranks the shared one`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemSharedCfg("GBA"), "rewind_enable = \"false\"")
        write(paths.systemOverrideCfg("GBA", launchCore), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["rewind_enable"])
    }

    @Test fun `a shared game override outranks the core-keyed system override`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideCfg("GBA", launchCore), "rewind_enable = \"false\"")
        write(paths.gameSharedCfg("GBA", "Game"), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["rewind_enable"])
    }

    @Test fun `a game override outranks the system override for the same core`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideCfg("GBA", launchCore), "rewind_enable = \"false\"")
        write(paths.gameOverrideCfg("GBA", "Game", launchCore), "rewind_enable = \"true\"")
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["rewind_enable"])
    }

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

    @Test fun `core options written for a different core are not composed in`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.systemOverrideOpt("GBA", "some_other_core"), "mgba_idle_opt = \"remove\"")

        launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertFalse(paths.coreOptionsLaunchOpt.readText().contains("mgba_idle_opt"))
    }

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

    @Test fun `a platform override beats the launcher default driver`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        every { settings.defaultVideoDriver } returns "vulkan"
        write(paths.systemOverrideCfg("GBA", launchCore), "video_driver = \"gl\"")

        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertEquals("gl", cfg["video_driver"])
    }

    @Test fun `the launcher default applies when nothing more specific says otherwise`() {
        val root = tmp.newFolder()
        every { settings.defaultVideoDriver } returns "vulkan"

        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertEquals("vulkan", cfg["video_driver"])
        assertTrue(CannoliPaths(root.absolutePath).globalOverrideCfg.isFile)
    }

    @Test fun `auto removes the generated global tier`() {
        val root = tmp.newFolder()
        val paths = CannoliPaths(root.absolutePath)
        write(paths.globalOverrideCfg, "video_driver = \"vulkan\"")
        every { settings.defaultVideoDriver } returns ""

        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))

        assertFalse(paths.globalOverrideCfg.exists())
        assertNotEquals("vulkan", cfg["video_driver"])
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
        assertEquals(File(root, "Saves/GBA/Game").absolutePath, cfg["savefile_directory"])
    }

    @Test fun `auto overrides are disabled in the launch config`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["auto_overrides_enable"])
    }

    @Test fun `auto remaps are disabled in the launch config`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["auto_remaps_enable"])
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
        assertEquals(File(root, "Saves/GBA/Game").absolutePath, cfg["savefile_directory"])
    }

    @Test fun `only a platform that forces the stick puts it on the D-pad, on every port`() {
        every { platformConfig.forcesStickDpad("NDS") } returns true
        val forcedRoot = tmp.newFolder()
        val forced = launchedConfig(forcedRoot, rom(forcedRoot, "Roms/NDS/Game.nds", "NDS"))
        val plainRoot = tmp.newFolder()
        val plain = launchedConfig(plainRoot, rom(plainRoot, "Roms/GBA/Game.gba", "GBA"))

        for (port in 1..16) assertEquals("3", forced["input_player${port}_analog_dpad_mode"])
        assertFalse(plain.containsKey("input_player1_analog_dpad_mode"))
    }

    @Test fun `a platform override outranks the forced stick default on its own port only`() {
        every { platformConfig.forcesStickDpad("NDS") } returns true
        val root = tmp.newFolder()
        write(CannoliPaths(root.absolutePath).systemSharedCfg("NDS"), "input_player1_analog_dpad_mode = \"1\"")

        val cfg = launchedConfig(root, rom(root, "Roms/NDS/Game.nds", "NDS"))

        assertEquals("1", cfg["input_player1_analog_dpad_mode"])
        assertEquals("3", cfg["input_player2_analog_dpad_mode"])
    }
}
