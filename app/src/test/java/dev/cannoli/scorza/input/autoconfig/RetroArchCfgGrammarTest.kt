package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// RetroArchCfgWriter's output is read by RetroArch, not by Cannoli's own parser, so a value that
// satisfies RetroArchCfgParser can still be rejected or silently misread by RetroArch. This test
// checks the writer against RetroArch's actual grammar, verified in the vendored source:
//
// - retroarch/configuration.c:7781, input_config_parse_joy_button, reads "<base>_btn" and accepts
//   ONLY: the literal "nul"; a hat of the form "h<digits><up|down|left|right>"; or an integer
//   parsed with strtoull, which is UNSIGNED, so a leading minus is not a valid button value.
// - retroarch/configuration.c:7694, input_config_parse_joy_axis, reads "<base>_axis" and accepts
//   ONLY: the literal "nul"; or a leading '+' or '-' followed by an axis index.
// - retroarch/input/input_driver.c:5915-5916 calls both parsers on the same base key, so
//   "input_<x>_btn" and "input_<x>_axis" are independent keys and either may be present
//   without the other.
class RetroArchCfgGrammarTest {

    private val hatBtnPattern = Regex("""^h\d+(up|down|left|right)$""")
    private val unsignedIntPattern = Regex("""^\d+$""")
    private val signedAxisPattern = Regex("""^[+-]\d+$""")
    private val linePattern = Regex("""^(input_[a-z0-9_]+|cannoli_[a-z0-9_]+) = "(.*)"$""")

    // These describe the device, not a binding, so RetroArch never reads them as a joy button
    // or axis key.
    private val nonBindingInputKeys = setOf(
        "input_driver",
        "input_device",
        "input_device_display_name",
        "input_vendor_id",
        "input_product_id",
    )

    private fun assertConformsToGrammar(cfg: String) {
        for (rawLine in cfg.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = linePattern.matchEntire(line)
            if (match == null) {
                fail("Line does not match the RetroArch cfg grammar (key = \"value\"): $line")
                continue
            }
            val (key, value) = match.destructured
            when {
                key.startsWith("cannoli_") -> Unit // RetroArch skips keys it does not know.
                key in nonBindingInputKeys -> Unit
                key.endsWith("_btn_label") || key.endsWith("_axis_label") -> Unit // Free text.
                key.endsWith("_btn") -> assertValidBtnValue(key, value)
                key.endsWith("_axis") -> assertValidAxisValue(key, value)
                else -> fail(
                    "Unrecognised key '$key' = \"$value\": either a new binding form that " +
                        "needs classifying here, or a typo in the writer."
                )
            }
        }
    }

    private fun assertValidBtnValue(key: String, value: String) {
        // The exact bug that shipped: a signed value on a _btn key. input_config_parse_joy_button
        // reads it with strtoull, which is unsigned, so this is called out explicitly rather than
        // folded into the pattern check below.
        assertFalse(
            "$key = \"$value\" is signed, but input_config_parse_joy_button reads _btn values " +
                "with strtoull (unsigned) -- this is the exact bug that shipped",
            value.startsWith("-"),
        )
        val valid = value == "nul" || hatBtnPattern.matches(value) || unsignedIntPattern.matches(value)
        assertTrue(
            "$key = \"$value\" is not a value input_config_parse_joy_button accepts " +
                "(nul, h<digits><up|down|left|right>, or a non-negative integer)",
            valid,
        )
    }

    private fun assertValidAxisValue(key: String, value: String) {
        val valid = value == "nul" || signedAxisPattern.matches(value)
        assertTrue(
            "$key = \"$value\" is not a value input_config_parse_joy_axis accepts " +
                "(nul, or a leading +/- followed by an axis index)",
            valid,
        )
    }

    // RetroArch's android_joypad_axis_state (retroarch/input/drivers_joypad/android_joypad.c)
    // bounds-checks the axis slot against MAX_AXIS(10) before reading android->analog_state, so
    // any slot >= 10 written into a cfg is a dead binding in native RetroArch.
    private fun assertAxisSlotsInBounds(cfg: String) {
        for (rawLine in cfg.lineSequence()) {
            val line = rawLine.trim()
            val match = linePattern.matchEntire(line) ?: continue
            val (key, value) = match.destructured
            if (!key.endsWith("_axis")) continue
            val slot = signedAxisPattern.matchEntire(value)?.value?.drop(1)?.toIntOrNull() ?: continue
            assertTrue(
                "$key = \"$value\" writes RA analog slot $slot, but android_joypad_axis_state " +
                    "bounds-checks against MAX_AXIS(10); slot $slot is a dead binding in native RetroArch",
                slot < 10,
            )
        }
    }

    private fun stickAxis(axis: Int): List<InputBinding> = listOf(
        InputBinding.Axis(
            axis = axis, restingValue = -1f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
        ),
        InputBinding.Axis(
            axis = axis, restingValue = 1f, activeMin = 0f, activeMax = -1f,
            digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK,
        ),
    )

    // Digital buttons, both analog triggers, and both left/right sticks reported on the RA-known
    // axes (0/1 and 11/14, the Z/RZ convention RaAxisSlots.kt maps to slots 2/3). The d-pad is
    // supplied separately below since one d-pad cannot be both hat-bound and axis-bound at once.
    private fun baseMapping(dpad: Map<CanonicalButton, List<InputBinding>>) = DeviceMapping(
        id = "Grammar_Test_Pad",
        displayName = "Grammar Test Pad",
        match = DeviceMatchRule(
            name = "Grammar Test Pad",
            vendorId = 1,
            productId = 2,
            androidBuildModel = "TestModel",
            sourceMask = 4,
            builtin = false,
        ),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
            CanonicalButton.BTN_WEST to listOf(InputBinding.Button(99)),
            CanonicalButton.BTN_NORTH to listOf(InputBinding.Button(100)),
            CanonicalButton.BTN_L to listOf(InputBinding.Button(102)),
            CanonicalButton.BTN_R to listOf(InputBinding.Button(103)),
            CanonicalButton.BTN_L3 to listOf(InputBinding.Button(106)),
            CanonicalButton.BTN_R3 to listOf(InputBinding.Button(107)),
            CanonicalButton.BTN_START to listOf(InputBinding.Button(108)),
            CanonicalButton.BTN_SELECT to listOf(InputBinding.Button(109)),
            CanonicalButton.BTN_MENU to listOf(InputBinding.Button(318)),
            // AXIS_LTRIGGER(17)/AXIS_RTRIGGER(18) -> RA slots 6/7.
            CanonicalButton.BTN_L2 to listOf(
                InputBinding.Axis(
                    axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                )
            ),
            CanonicalButton.BTN_R2 to listOf(
                InputBinding.Axis(
                    axis = 18, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
                )
            ),
            CanonicalButton.BTN_LSTICK_X to stickAxis(0),
            CanonicalButton.BTN_LSTICK_Y to stickAxis(1),
            CanonicalButton.BTN_RSTICK_X to stickAxis(11),
            CanonicalButton.BTN_RSTICK_Y to stickAxis(14),
        ) + dpad,
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
        glyphStyle = GlyphStyle.SHAPES,
        excludeFromGameplay = false,
        defaultControllerTypeId = 1,
        source = MappingSource.USER_WIZARD,
        userEdited = true,
    )

    private val hatDpad: Map<CanonicalButton, List<InputBinding>> = mapOf(
        CanonicalButton.BTN_UP to listOf(InputBinding.Hat(16, HatDirection.UP)),
        CanonicalButton.BTN_DOWN to listOf(InputBinding.Hat(16, HatDirection.DOWN)),
        CanonicalButton.BTN_LEFT to listOf(InputBinding.Hat(15, HatDirection.LEFT)),
        CanonicalButton.BTN_RIGHT to listOf(InputBinding.Hat(15, HatDirection.RIGHT)),
    )

    // A pad whose d-pad reports as a plain digital push on AXIS_X/AXIS_Y rather than a hat.
    private val axisDpad: Map<CanonicalButton, List<InputBinding>> = mapOf(
        CanonicalButton.BTN_UP to listOf(
            InputBinding.Axis(
                axis = 1, restingValue = 0f, activeMin = 0f, activeMax = -1f,
                digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
            )
        ),
        CanonicalButton.BTN_DOWN to listOf(
            InputBinding.Axis(
                axis = 1, restingValue = 0f, activeMin = 0f, activeMax = 1f,
                digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
            )
        ),
        CanonicalButton.BTN_LEFT to listOf(
            InputBinding.Axis(
                axis = 0, restingValue = 0f, activeMin = 0f, activeMax = -1f,
                digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
            )
        ),
        CanonicalButton.BTN_RIGHT to listOf(
            InputBinding.Axis(
                axis = 0, restingValue = 0f, activeMin = 0f, activeMax = 1f,
                digitalThreshold = 0.5f, analogRole = AnalogRole.DIGITAL_BUTTON,
            )
        ),
    )

    @Test
    fun `writer output conforms to RetroArch's cfg grammar for a hat-bound dpad`() {
        val mapping = baseMapping(hatDpad)
        assertConformsToGrammar(RetroArchCfgWriter.write(mapping, debugBuild = true))
        assertConformsToGrammar(RetroArchCfgWriter.write(mapping, debugBuild = false))
    }

    @Test
    fun `writer output conforms to RetroArch's cfg grammar for an axis-bound dpad`() {
        val mapping = baseMapping(axisDpad)
        assertConformsToGrammar(RetroArchCfgWriter.write(mapping, debugBuild = true))
        assertConformsToGrammar(RetroArchCfgWriter.write(mapping, debugBuild = false))
    }

    @Test
    fun `every axis slot the writer emits for the standard mapping stays under MAX_AXIS`() {
        assertAxisSlotsInBounds(RetroArchCfgWriter.write(baseMapping(hatDpad)))
        assertAxisSlotsInBounds(RetroArchCfgWriter.write(baseMapping(axisDpad)))
    }

    // RA's Android driver never reads AXIS_RX(12)/AXIS_RY(13) into analog_state, so a right stick
    // captured on that convention (some pads report it this way instead of AXIS_Z/AXIS_RZ) has no
    // RA slot at all. It must be left out rather than named by its raw android id, which would
    // write slot 12/13 and be rejected as >= MAX_AXIS(10).
    @Test
    fun `a right stick reported on AXIS_RX or AXIS_RY writes no key at all`() {
        val cfg = RetroArchCfgWriter.write(mappingWithRightStickOn(12, 13))
        assertAxisSlotsInBounds(cfg)
        assertNoKeyStartingWith(cfg, "input_r_x_")
        assertNoKeyStartingWith(cfg, "input_r_y_")
    }

    // The failure mode that does not announce itself. Android axes 2..9 are pressure, size and
    // touch axes rather than joystick axes, so passing one through unchanged yields an IN-RANGE
    // slot naming a different control: android axis 8 would write slot 8, which RA reads as
    // AXIS_BRAKE. A wrong binding is worse than a missing one, so nothing is written.
    @Test
    fun `an axis with no RA slot never borrows an in-range slot from another control`() {
        val cfg = RetroArchCfgWriter.write(mappingWithRightStickOn(8, 9))
        assertNoKeyStartingWith(cfg, "input_r_x_")
        assertNoKeyStartingWith(cfg, "input_r_y_")
    }

    // The skip must not consume the key: a pad reporting one stick axis on an unmappable axis and
    // another on a real one still writes the one RA can use.
    @Test
    fun `an unmappable binding does not suppress a mappable one on the same key`() {
        val mapping = baseMapping(hatDpad).let {
            it.copy(bindings = it.bindings + mapOf(CanonicalButton.BTN_RSTICK_X to (stickAxis(12) + stickAxis(11))))
        }
        val cfg = RetroArchCfgWriter.write(mapping)
        assertAxisSlotsInBounds(cfg)
        assertTrue(
            "the AXIS_Z(11) binding should still claim input_r_x_plus_axis as RA slot 2",
            cfg.lineSequence().any { it.trim() == "input_r_x_plus_axis = \"+2\"" },
        )
    }

    private fun mappingWithRightStickOn(xAxis: Int, yAxis: Int) = baseMapping(hatDpad).let {
        it.copy(
            bindings = it.bindings + mapOf(
                CanonicalButton.BTN_RSTICK_X to stickAxis(xAxis),
                CanonicalButton.BTN_RSTICK_Y to stickAxis(yAxis),
            )
        )
    }

    private fun assertNoKeyStartingWith(cfg: String, prefix: String) {
        val offending = cfg.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }.toList()
        assertTrue(
            "expected no '$prefix' key, since that axis has no RA slot, but found: $offending",
            offending.isEmpty(),
        )
    }
}
