package dev.cannoli.scorza.launcher

import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CheevosConfigTest pins the emitted keys; this pins the wiring, so a launch that never reads the
 * account, or a hardcore launch that still writes the auto slot, fails here.
 */
class CheevosLaunchConfigTest : LaunchConfigHarness() {

    private fun loggedIn(hardcore: Boolean = false) {
        every { settings.raUsername } returns "bob"
        every { settings.raToken } returns "abc123"
        every { settings.raHardcore } returns hardcore
        every { settings.alwaysSaveOnQuit } returns true
    }

    @Test fun `a logged in launch writes the account keys`() {
        val root = tmp.newFolder()
        loggedIn()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["cheevos_enable"])
        assertEquals("bob", cfg["cheevos_username"])
        assertEquals("abc123", cfg["cheevos_token"])
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertFalse(cfg.containsKey("cheevos_password"))
    }

    @Test fun `a logged out launch writes no cheevos keys`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertTrue(cfg.keys.none { it.startsWith("cheevos_") })
    }

    @Test fun `a hardcore launch writes neither auto state key`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val cfg = resumedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["cheevos_hardcore_mode_enable"])
        assertFalse(cfg.containsKey("savestate_auto_save"))
        assertFalse(cfg.containsKey("savestate_auto_load"))
    }

    @Test fun `a force softcore game keeps both auto state keys under global hardcore`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val cfg = resumedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA", forceSoftcore = true))
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertEquals("true", cfg["savestate_auto_save"])
        assertEquals("true", cfg["savestate_auto_load"])
    }

    @Test fun `a hardcore game is not resumable`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val mgr = manager(root)
        val rom = rom(root, "Roms/GBA/Game.gba", "GBA").withSaveState(mgr)
        assertTrue(mgr.findResumableRoms(listOf(rom)).isEmpty())
    }

    @Test fun `a force softcore game stays resumable under global hardcore`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val mgr = manager(root)
        val rom = rom(root, "Roms/GBA/Game.gba", "GBA", forceSoftcore = true).withSaveState(mgr)
        assertEquals(setOf(rom.path.absolutePath), mgr.findResumableRoms(listOf(rom)))
    }

    private fun dev.cannoli.scorza.model.Rom.withSaveState(mgr: LaunchManager) = also {
        java.io.File(mgr.saveStateBasePath(it)).apply { parentFile!!.mkdirs(); writeText("state") }
    }
}
