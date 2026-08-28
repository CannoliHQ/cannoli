package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaBindingKeysTest {

    @Test fun `every canonical round trips through its cfg key`() {
        for (entry in RaButtonKey.entries) {
            assertEquals(entry, RaButtonKey.forCanonical(entry.canonical))
            assertEquals(entry.canonical, RaButtonKey.forCfgKey(entry.cfgKey)?.canonical)
        }
    }

    @Test fun `cfg keys and canonicals are both unique`() {
        assertEquals(RaButtonKey.entries.size, RaButtonKey.entries.map { it.cfgKey }.toSet().size)
        assertEquals(RaButtonKey.entries.size, RaButtonKey.entries.map { it.canonical }.toSet().size)
        assertEquals(RaAxisKey.entries.size, RaAxisKey.entries.map { it.cfgKey }.toSet().size)
    }

    @Test fun `the face buttons stay crossed the RetroArch way`() {
        assertEquals("b_btn", RaButtonKey.forCanonical(CanonicalButton.BTN_SOUTH)?.cfgKey)
        assertEquals("a_btn", RaButtonKey.forCanonical(CanonicalButton.BTN_EAST)?.cfgKey)
        assertEquals("y_btn", RaButtonKey.forCanonical(CanonicalButton.BTN_WEST)?.cfgKey)
        assertEquals("x_btn", RaButtonKey.forCanonical(CanonicalButton.BTN_NORTH)?.cfgKey)
        assertEquals(CanonicalButton.BTN_SOUTH, RaButtonKey.forCfgKey("b_btn")?.canonical)
        assertEquals(CanonicalButton.BTN_EAST, RaButtonKey.forCfgKey("a_btn")?.canonical)
        assertEquals(CanonicalButton.BTN_WEST, RaButtonKey.forCfgKey("y_btn")?.canonical)
        assertEquals(CanonicalButton.BTN_NORTH, RaButtonKey.forCfgKey("x_btn")?.canonical)
    }

    @Test fun `only the stick canonicals lack a button key`() {
        val unmapped = CanonicalButton.entries.filter { RaButtonKey.forCanonical(it) == null }
        assertEquals(
            listOf(
                CanonicalButton.BTN_LSTICK_X,
                CanonicalButton.BTN_LSTICK_Y,
                CanonicalButton.BTN_RSTICK_X,
                CanonicalButton.BTN_RSTICK_Y,
            ),
            unmapped,
        )
    }

    @Test fun `the button cfg keys are exactly the ones RetroArch reads`() {
        assertEquals(
            setOf(
                "a_btn", "b_btn", "x_btn", "y_btn",
                "l_btn", "r_btn",
                "l2_btn", "r2_btn",
                "l3_btn", "r3_btn",
                "start_btn", "select_btn",
                "up_btn", "down_btn", "left_btn", "right_btn",
                "menu_toggle_btn",
            ),
            RaButtonKey.CFG_KEYS,
        )
    }

    @Test fun `the axis cfg keys are exactly the ones RetroArch reads`() {
        assertEquals(
            setOf(
                "l2_axis", "r2_axis",
                "up_axis", "down_axis", "left_axis", "right_axis",
                "l_x_plus_axis", "l_x_minus_axis",
                "l_y_plus_axis", "l_y_minus_axis",
                "r_x_plus_axis", "r_x_minus_axis",
                "r_y_plus_axis", "r_y_minus_axis",
            ),
            RaAxisKey.CFG_KEYS,
        )
    }

    @Test fun `a trigger or d-pad axis key serves either sign`() {
        for (canonical in listOf(
            CanonicalButton.BTN_L2, CanonicalButton.BTN_R2,
            CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
            CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
        )) {
            val positive = RaAxisKey.forBinding(canonical, AnalogRole.DIGITAL_BUTTON, positive = true)
            val negative = RaAxisKey.forBinding(canonical, AnalogRole.DIGITAL_BUTTON, positive = false)
            assertEquals(positive, negative)
            assertEquals(canonical, positive?.canonical)
        }
    }

    @Test fun `a stick axis key carries its direction`() {
        assertEquals(
            "l_x_plus_axis",
            RaAxisKey.forBinding(CanonicalButton.BTN_LSTICK_X, AnalogRole.ANALOG_STICK, positive = true)?.cfgKey,
        )
        assertEquals(
            "l_x_minus_axis",
            RaAxisKey.forBinding(CanonicalButton.BTN_LSTICK_X, AnalogRole.ANALOG_STICK, positive = false)?.cfgKey,
        )
        assertEquals(
            "r_y_plus_axis",
            RaAxisKey.forBinding(CanonicalButton.BTN_RSTICK_Y, AnalogRole.ANALOG_STICK, positive = true)?.cfgKey,
        )
        assertEquals(
            "r_y_minus_axis",
            RaAxisKey.forBinding(CanonicalButton.BTN_RSTICK_Y, AnalogRole.ANALOG_STICK, positive = false)?.cfgKey,
        )
    }

    @Test fun `a role the canonical does not carry has no axis key`() {
        assertNull(RaAxisKey.forBinding(CanonicalButton.BTN_L2, AnalogRole.ANALOG_STICK, positive = true))
        assertNull(RaAxisKey.forBinding(CanonicalButton.BTN_LSTICK_X, AnalogRole.DIGITAL_BUTTON, positive = true))
        assertNull(RaAxisKey.forBinding(CanonicalButton.BTN_SOUTH, AnalogRole.DIGITAL_BUTTON, positive = true))
    }

    // A writer-only key would survive the parser as an unmodeled line and then be written twice.
    @Test fun `every key the writer can emit is a managed key`() {
        for (key in RaButtonKey.CFG_KEYS + RaAxisKey.CFG_KEYS) {
            assertEquals(true, "input_$key" in RetroArchCfgEntry.MANAGED_KEYS)
        }
    }
}
