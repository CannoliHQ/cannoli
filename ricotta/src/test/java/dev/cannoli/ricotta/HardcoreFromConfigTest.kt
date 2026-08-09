package dev.cannoli.ricotta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RetroArch defaults cheevos_hardcore_mode_enable to true and Cannoli writes no cheevos key at all
 * without a token, so the hardcore key on its own reads "true" on a config that names neither.
 * These pin the pairing that keeps the save state rows off only when hardcore is really running.
 */
class HardcoreFromConfigTest {

    private fun hardcore(cheevos: String?, mode: String?) =
        EmbeddedRetroArchBridge.hardcoreFromConfig(cheevos, mode)

    @Test fun `both keys on is hardcore`() {
        assertTrue(hardcore("true", "true"))
    }

    @Test fun `a config with no cheevos keys is not hardcore`() {
        assertFalse(hardcore("false", "true"))
    }

    @Test fun `a force softcore game is not in hardcore`() {
        assertFalse(hardcore("true", "false"))
    }

    @Test fun `an unreadable setting is not hardcore`() {
        assertFalse(hardcore(null, "true"))
        assertFalse(hardcore("true", null))
    }
}
