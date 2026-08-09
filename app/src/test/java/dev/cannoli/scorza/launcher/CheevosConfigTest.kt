package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RetroArch defaults cheevos_hardcore_mode_enable to true, so enabling cheevos without stating
 * hardcore silently turns it on. Every enabled emission pins it.
 */
class CheevosConfigTest {

    private fun keys(
        username: String = "bob",
        token: String = "abc123",
        hardcore: Boolean = false,
        forceSoftcore: Boolean = false,
    ) = LaunchManager.cheevosOverrides(username, token, hardcore, forceSoftcore)

    @Test fun `a logged in account emits the account keys`() {
        val k = keys()
        assertEquals("true", k["cheevos_enable"])
        assertEquals("bob", k["cheevos_username"])
        assertEquals("abc123", k["cheevos_token"])
        assertEquals("true", k["cheevos_verbose_enable"])
        assertEquals("false", k["cheevos_badges_enable"])
    }

    // Stated empty rather than omitted: Cannoli never launches with a password, and an inherited
    // base config can carry one, so the launch has to say so instead of leaving it alone.
    @Test fun `the password is always emitted empty`() {
        assertEquals("", keys()["cheevos_password"])
        assertEquals("", keys(token = "")["cheevos_password"])
    }

    @Test fun `hardcore is always stated`() {
        assertEquals("false", keys(hardcore = false)["cheevos_hardcore_mode_enable"])
        assertEquals("true", keys(hardcore = true)["cheevos_hardcore_mode_enable"])
    }

    @Test fun `a per game force softcore beats the global toggle`() {
        assertEquals("false", keys(hardcore = true, forceSoftcore = true)["cheevos_hardcore_mode_enable"])
    }

    @Test fun `hardcore is in effect only when the emission turns it on`() {
        assertTrue(LaunchManager.hardcoreInEffect(keys(hardcore = true)))
        assertFalse(LaunchManager.hardcoreInEffect(keys(hardcore = false)))
    }

    @Test fun `a force softcore game keeps its save states`() {
        assertFalse(LaunchManager.hardcoreInEffect(keys(hardcore = true, forceSoftcore = true)))
    }

    @Test fun `a logged out player keeps save states whatever the toggle says`() {
        assertFalse(LaunchManager.hardcoreInEffect(keys(token = "", hardcore = true)))
        assertFalse(LaunchManager.hardcoreInEffect(keys(username = "", hardcore = true)))
    }

    @Test fun `hardcore writes neither auto state key`() {
        assertTrue(
            LaunchManager.autoStateOverrides(hardcore = true, resume = true, alwaysSaveOnQuit = true).isEmpty()
        )
    }

    @Test fun `a force softcore game still writes both auto state keys`() {
        val k = LaunchManager.autoStateOverrides(
            hardcore = LaunchManager.hardcoreInEffect(keys(hardcore = true, forceSoftcore = true)),
            resume = true,
            alwaysSaveOnQuit = true,
        )
        assertEquals("true", k["savestate_auto_save"])
        assertEquals("true", k["savestate_auto_load"])
    }

    @Test fun `outside hardcore the auto save still follows always save on quit`() {
        val off = LaunchManager.autoStateOverrides(hardcore = false, resume = false, alwaysSaveOnQuit = false)
        assertEquals("false", off["savestate_auto_save"])
        assertNull(off["savestate_auto_load"])
    }

    // No account means every session key is stated blank, not skipped. Skipping them is what let an
    // inherited base config keep a stranger's account, token and hardcore live for a logged out
    // player: an unstated key leaves the inherited value untouched in the launch config.
    @Test fun `no token scrubs cheevos rather than emitting nothing`() {
        for (k in listOf(keys(token = "", hardcore = true), keys(username = "", hardcore = true))) {
            assertEquals("false", k["cheevos_enable"])
            assertEquals("false", k["cheevos_hardcore_mode_enable"])
            assertEquals("", k["cheevos_username"])
            assertEquals("", k["cheevos_token"])
            assertEquals("", k["cheevos_password"])
        }
    }

    // The five session/account/mode keys are the whole denylist the security fix guards, so every
    // emission has to state all five whatever the account state.
    @Test fun `every session key is stated in both account states`() {
        val sessionKeys = listOf(
            "cheevos_enable",
            "cheevos_hardcore_mode_enable",
            "cheevos_username",
            "cheevos_token",
            "cheevos_password",
        )
        for (k in listOf(keys(), keys(token = ""), keys(username = ""))) {
            for (sk in sessionKeys) assertTrue(sk, k.containsKey(sk))
        }
    }

    @Test fun `force softcore alone does not enable cheevos`() {
        assertEquals("false", keys(token = "", forceSoftcore = true)["cheevos_enable"])
    }

    @Test fun `hardcore is off unless the emission says otherwise`() {
        assertFalse(keys().containsValue("cheevos_password"))
        assertEquals("false", keys()["cheevos_hardcore_mode_enable"])
    }
}
