package dev.cannoli.scorza.launcher

import io.mockk.every
import io.mockk.slot
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
        assertEquals("", cfg["cheevos_password"])
    }

    @Test fun `a logged out launch turns cheevos off rather than staying silent`() {
        val root = tmp.newFolder()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["cheevos_enable"])
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertEquals("", cfg["cheevos_username"])
        assertEquals("", cfg["cheevos_token"])
        assertEquals("", cfg["cheevos_password"])
    }

    /**
     * Seeds a stale cheevos block into the base config. syncRetroArchConfig now regenerates the
     * base every launch, so the seed is overwritten before buildGameConfig reads it; these cases
     * therefore duplicate the plain logged-in/out cases and simply reconfirm the plumbing wins.
     */
    private fun seedInheritedCheevosBlock(root: java.io.File) {
        val cfg = java.io.File(
            dev.cannoli.scorza.config.CannoliPaths(root.absolutePath).configRetroArch,
            "retroarch.cfg",
        )
        cfg.parentFile!!.mkdirs()
        cfg.writeText(
            """
            cheevos_enable = "true"
            cheevos_username = "olduser"
            cheevos_token = "oldtoken"
            cheevos_password = "hunter2"
            cheevos_hardcore_mode_enable = "true"
            """.trimIndent()
        )
    }

    @Test fun `a logged out launch scrubs an inherited cheevos block`() {
        val root = tmp.newFolder()
        seedInheritedCheevosBlock(root)
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("false", cfg["cheevos_enable"])
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertEquals("", cfg["cheevos_username"])
        assertEquals("", cfg["cheevos_token"])
        assertEquals("", cfg["cheevos_password"])
        // Not hardcore, so the save state rows and the auto slot stay.
        assertTrue(cfg.containsKey("savestate_auto_save"))
    }

    @Test fun `a logged in launch overrides every inherited session key`() {
        val root = tmp.newFolder()
        seedInheritedCheevosBlock(root)
        loggedIn()
        val cfg = launchedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertEquals("true", cfg["cheevos_enable"])
        assertEquals("bob", cfg["cheevos_username"])
        assertEquals("abc123", cfg["cheevos_token"])
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertEquals("", cfg["cheevos_password"])
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

    // A manual Game ID forces softcore even when the user never set the toggle: the association is
    // unofficial, so hardcore would be invalid, and it must not depend on rom.forceSoftcore.
    @Test fun `a game id forces softcore under global hardcore`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val cfg = resumedConfig(root, rom(root, "Roms/GBA/Game.gba", "GBA", raGameId = 4321))
        assertEquals("false", cfg["cheevos_hardcore_mode_enable"])
        assertEquals("true", cfg["savestate_auto_save"])
        assertEquals("true", cfg["savestate_auto_load"])
    }

    @Test fun `a game id launch carries a softcore flag to the igm`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val igm = slot<RicottaIgm>()
        every { retroArchLauncher.launchRicotta(any(), any(), any(), capture(igm)) } returns LaunchResult.Success
        manager(root).launchRom(rom(root, "Roms/GBA/Game.gba", "GBA", raGameId = 4321))
        assertFalse(igm.captured.hardcoreInEffect)
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

    // The IGM gates its save state rows on this parcel flag, so a force-softcore game under global
    // hardcore must launch with the flag off (rows shown) even though global hardcore is on.
    @Test fun `a force softcore launch carries a softcore flag to the igm`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val igm = slot<RicottaIgm>()
        every { retroArchLauncher.launchRicotta(any(), any(), any(), capture(igm)) } returns LaunchResult.Success
        manager(root).launchRom(rom(root, "Roms/GBA/Game.gba", "GBA", forceSoftcore = true))
        assertFalse(igm.captured.hardcoreInEffect)
    }

    @Test fun `a hardcore launch carries a hardcore flag to the igm`() {
        val root = tmp.newFolder()
        loggedIn(hardcore = true)
        val igm = slot<RicottaIgm>()
        every { retroArchLauncher.launchRicotta(any(), any(), any(), capture(igm)) } returns LaunchResult.Success
        manager(root).launchRom(rom(root, "Roms/GBA/Game.gba", "GBA"))
        assertTrue(igm.captured.hardcoreInEffect)
    }

    private fun dev.cannoli.scorza.model.Rom.withSaveState(mgr: LaunchManager) = also {
        java.io.File(mgr.saveStateBasePath(it)).apply { parentFile!!.mkdirs(); writeText("state") }
    }
}
