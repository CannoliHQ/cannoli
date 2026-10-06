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

class RetroArchCfgGrammarTest {

    private val hatBtnPattern = Regex("""^h\d+(up|down|left|right)$""")
    private val unsignedIntPattern = Regex("""^\d+$""")
    private val signedAxisPattern = Regex("""^[+-]\d+$""")
    private val linePattern = Regex("""^(input_[a-z0-9_]+|cannoli_[a-z0-9_]+) = "(.*)"$""")

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
                key.startsWith("cannoli_") -> Unit
                key in nonBindingInputKeys -> Unit
                key.endsWith("_btn_label") || key.endsWith("_axis_label") -> Unit
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

    @Test
    fun `a right stick reported on AXIS_RX or AXIS_RY writes no key at all`() {
        val cfg = RetroArchCfgWriter.write(mappingWithRightStickOn(12, 13))
        assertAxisSlotsInBounds(cfg)
        assertNoKeyStartingWith(cfg, "input_r_x_")
        assertNoKeyStartingWith(cfg, "input_r_y_")
    }

    @Test
    fun `an axis with no RA slot never borrows an in-range slot from another control`() {
        val cfg = RetroArchCfgWriter.write(mappingWithRightStickOn(8, 9))
        assertNoKeyStartingWith(cfg, "input_r_x_")
        assertNoKeyStartingWith(cfg, "input_r_y_")
    }

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
