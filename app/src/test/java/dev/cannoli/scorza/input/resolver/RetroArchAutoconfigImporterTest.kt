package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.AxisRef
import dev.cannoli.scorza.input.autoconfig.CfgHatDirection
import dev.cannoli.scorza.input.autoconfig.HatRef
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgParser
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroArchAutoconfigImporterTest {

    private val device = ConnectedDevice(
        androidDeviceId = 7,
        descriptor = "abc",
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        androidBuildModel = "Pixel",
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    private fun deviceNamed(
        name: String,
        vendorId: Int,
        productId: Int,
        androidBuildModel: String = "Pixel",
    ) = ConnectedDevice(
        androidDeviceId = 7,
        descriptor = "abc",
        name = name,
        vendorId = vendorId,
        productId = productId,
        androidBuildModel = androidBuildModel,
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    @Test fun `id is the name slug with no descriptor suffix`() {
        val resolved = RetroArchAutoconfigImporter.import(
            RetroArchCfgEntry(deviceName = "Wireless Controller", vendorId = 1356, productId = 2508, buttonBindings = emptyMap()),
            deviceNamed("Wireless Controller", 1356, 2508),
        )
        assertEquals("ra_wireless_controller", resolved.id)
    }

    @Test fun `pinned entries carry the model in the id`() {
        val resolved = RetroArchAutoconfigImporter.import(
            RetroArchCfgEntry(
                deviceName = "Retroid Pocket Controller",
                vendorId = null, productId = null,
                buildModel = "Retroid Pocket Nova",
                buttonBindings = emptyMap(),
            ),
            deviceNamed("Retroid Pocket Controller", 8226, 12289, "Retroid Pocket Nova"),
        )
        assertEquals("ra_retroid_pocket_controller_retroid_pocket_nova", resolved.id)
    }

    @Test
    fun translates_face_and_dpad_buttons() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf(
                "b_btn" to 96, "a_btn" to 97, "y_btn" to 99, "x_btn" to 100,
                "up_btn" to 19, "down_btn" to 20, "left_btn" to 21, "right_btn" to 22,
                "start_btn" to 108, "select_btn" to 109,
            ),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, t.source)
        assertEquals(InputBinding.Button(96), t.bindings[CanonicalButton.BTN_SOUTH]!![0])
        assertEquals(InputBinding.Button(97), t.bindings[CanonicalButton.BTN_EAST]!![0])
        assertEquals(InputBinding.Button(19), t.bindings[CanonicalButton.BTN_UP]!![0])
        assertEquals(InputBinding.Button(108), t.bindings[CanonicalButton.BTN_START]!![0])
    }

    @Test
    fun l2_axis_with_positive_direction_becomes_digital_axis_binding() {
        // RA slot 6 is AXIS_LTRIGGER (android id 17); the cfg number is a slot index, not the
        // android axis id itself.
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = emptyMap(),
            axisBindings = mapOf("l2_axis" to AxisRef(axis = 6, direction = +1)),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        val l2 = t.bindings[CanonicalButton.BTN_L2]!![0] as InputBinding.Axis
        assertEquals(17, l2.axis)
        // Trigger axes are unipolar: rest at 0, full press at +1 for direction=+1. A bipolar
        // mapping would normalize axis-at-rest (0) to 0.5, past the 0.5 digital threshold,
        // which would leave the trigger reading "barely pressed" forever.
        assertEquals(0f, l2.restingValue, 0.001f)
        assertEquals(0f, l2.activeMin, 0.001f)
        assertEquals(1f, l2.activeMax, 0.001f)
        assertEquals(AnalogRole.DIGITAL_BUTTON, l2.analogRole)
    }

    @Test
    fun axis_notation_on_a_dpad_key_becomes_a_digital_axis_binding() {
        val entry = RetroArchCfgEntry(
            deviceName = "M30", vendorId = 1, productId = 2,
            buttonBindings = emptyMap(),
            axisBindings = mapOf("up_axis" to AxisRef(axis = 1, direction = -1)),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        val up = t.bindings[CanonicalButton.BTN_UP]!!.single() as InputBinding.Axis
        assertEquals(1, up.axis)
        assertEquals(AnalogRole.DIGITAL_BUTTON, up.analogRole)
        assertEquals(-1f, up.activeMax, 0.001f)
    }

    @Test
    fun legacy_signed_axis_value_on_a_dpad_btn_key_still_imports_to_a_digital_axis_binding() {
        // Cannoli briefly wrote input_up_btn = "-1" for an axis-reported d-pad before the writer
        // was fixed to emit input_up_axis. RetroArch never accepted this form, but a user's cfg
        // may still carry it, so this proves the parser's read-only tolerance imports it to the
        // same binding a native input_up_axis line produces.
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "M30"
            input_up_btn = "-1"
            """.trimIndent(),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        val up = t.bindings[CanonicalButton.BTN_UP]!!.single() as InputBinding.Axis
        assertEquals(1, up.axis)
        assertEquals(AnalogRole.DIGITAL_BUTTON, up.analogRole)
        assertEquals(-1f, up.activeMax, 0.001f)
    }

    @Test
    fun stick_axes_key_under_stick_canonicals_and_l3_r3_hold_only_click_buttons() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf("l3_btn" to 106, "r3_btn" to 107),
            axisBindings = mapOf(
                "l_x_plus_axis" to AxisRef(0, +1),
                "l_x_minus_axis" to AxisRef(0, -1),
                "l_y_plus_axis" to AxisRef(1, +1),
                "l_y_minus_axis" to AxisRef(1, -1),
                "r_x_plus_axis" to AxisRef(2, +1),
                "r_x_minus_axis" to AxisRef(2, -1),
                "r_y_plus_axis" to AxisRef(3, +1),
                "r_y_minus_axis" to AxisRef(3, -1),
            ),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)

        // Sticks key under the stick canonicals now, still bipolar per axisRange: rest at the
        // opposite extreme, active span crossing 0 to the pressed extreme. RA slots 0/1 (left
        // stick) coincide with their android axis ids; slots 2/3 (right stick) translate to
        // android AXIS_Z (11) and AXIS_RZ (14).
        assertEquals(
            listOf(
                InputBinding.Axis(0, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(0, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            t.bindings[CanonicalButton.BTN_LSTICK_X],
        )
        assertEquals(
            listOf(
                InputBinding.Axis(1, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(1, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            t.bindings[CanonicalButton.BTN_LSTICK_Y],
        )
        assertEquals(
            listOf(
                InputBinding.Axis(11, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(11, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            t.bindings[CanonicalButton.BTN_RSTICK_X],
        )
        assertEquals(
            listOf(
                InputBinding.Axis(14, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
                InputBinding.Axis(14, restingValue = 1f, activeMin = 0f, activeMax = -1f, digitalThreshold = 0.5f, analogRole = AnalogRole.ANALOG_STICK),
            ),
            t.bindings[CanonicalButton.BTN_RSTICK_Y],
        )

        // The click buttons stay on BTN_L3/BTN_R3 -- only their axes moved off.
        assertEquals(listOf(InputBinding.Button(106)), t.bindings[CanonicalButton.BTN_L3])
        assertEquals(listOf(InputBinding.Button(107)), t.bindings[CanonicalButton.BTN_R3])
    }

    @Test
    fun `cfg axis slot numbers translate to android axis ids on import`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_driver = "android"
            input_l_x_plus_axis = "+0"
            input_r_x_plus_axis = "+2"
            input_l2_axis = "+8"
            """.trimIndent(),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        val lx = t.bindings[CanonicalButton.BTN_LSTICK_X]!![0] as InputBinding.Axis
        val rx = t.bindings[CanonicalButton.BTN_RSTICK_X]!![0] as InputBinding.Axis
        val l2 = t.bindings[CanonicalButton.BTN_L2]!![0] as InputBinding.Axis
        assertEquals(0, lx.axis)
        assertEquals(11, rx.axis)
        assertEquals(23, l2.axis)
    }

    @Test
    fun match_rule_uses_entry_vid_pid_and_name() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf("b_btn" to 96),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals("Stadia Controller", t.match.name)
        assertEquals(6353, t.match.vendorId)
        assertEquals(37888, t.match.productId)
    }

    @Test
    fun hat_notation_dpad_becomes_canonical_directional_hat_bindings() {
        val entry = RetroArchCfgEntry(
            deviceName = "Retroid Pocket Controller",
            vendorId = 8226,
            productId = 12289,
            buttonBindings = mapOf("b_btn" to 96),
            hatBindings = mapOf(
                "up_btn" to HatRef(0, CfgHatDirection.UP),
                "down_btn" to HatRef(0, CfgHatDirection.DOWN),
                "left_btn" to HatRef(0, CfgHatDirection.LEFT),
                "right_btn" to HatRef(0, CfgHatDirection.RIGHT),
            ),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)

        val up = t.bindings[CanonicalButton.BTN_UP]?.firstOrNull()
        val down = t.bindings[CanonicalButton.BTN_DOWN]?.firstOrNull()
        val left = t.bindings[CanonicalButton.BTN_LEFT]?.firstOrNull()
        val right = t.bindings[CanonicalButton.BTN_RIGHT]?.firstOrNull()

        org.junit.Assert.assertTrue(up is InputBinding.Hat && up.axis == 16 && up.direction == HatDirection.UP)
        org.junit.Assert.assertTrue(down is InputBinding.Hat && down.axis == 16 && down.direction == HatDirection.DOWN)
        org.junit.Assert.assertTrue(left is InputBinding.Hat && left.axis == 15 && left.direction == HatDirection.LEFT)
        org.junit.Assert.assertTrue(right is InputBinding.Hat && right.axis == 15 && right.direction == HatDirection.RIGHT)
    }

    @Test fun `importer applies LegendResolver profile for matching VID`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Sony Pad",
            vendorId = 1356,
            productId = 2508,
            buttonBindings = mapOf("a_btn" to 97),
            axisBindings = emptyMap(),
            hatBindings = emptyMap(),
        )
        val device = ConnectedDevice(
            androidDeviceId = 1, descriptor = "d", name = "Sony Pad",
            vendorId = 1356, productId = 2508, androidBuildModel = "Pixel",
            sourceMask = 0, connectedAtMillis = 0,
        )
        val tpl = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(CanonicalButton.BTN_SOUTH, tpl.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, tpl.menuBack)
        assertEquals(GlyphStyle.SHAPES, tpl.glyphStyle)
    }

    @Test fun `cannoli menu keycodes replace the platform defaults`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf("b_btn" to 96, "menu_toggle_btn" to 318),
            cannoliUser = true,
            cannoliMenuKeycodes = listOf(318),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(listOf(InputBinding.Button(318)), t.bindings[CanonicalButton.BTN_MENU])
    }

    @Test fun `an empty cannoli menu keycode list clears the menu`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf("b_btn" to 96),
            cannoliUser = true,
            cannoliMenuKeycodes = emptyList(),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(emptyList<InputBinding>(), t.bindings[CanonicalButton.BTN_MENU].orEmpty())
    }

    @Test fun `absent cannoli menu keycodes keep the injected platform defaults`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = mapOf("b_btn" to 96),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals(
            listOf(InputBinding.Button(4), InputBinding.Button(110)),
            t.bindings[CanonicalButton.BTN_MENU],
        )
    }

    @Test fun `absent cannoli glyph and confirm keys fall to default not build model`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Pad", vendorId = 8224, productId = 273,
            buttonBindings = mapOf("b_btn" to 96, "a_btn" to 97),
        )
        val thorDevice = ConnectedDevice(
            androidDeviceId = 2, descriptor = "thor", name = "Pad",
            vendorId = 8224, productId = 273, androidBuildModel = "AYN Thor",
            sourceMask = 0, connectedAtMillis = 0,
        )
        val t = RetroArchAutoconfigImporter.import(entry, thorDevice)
        assertEquals(GlyphStyle.REDMOND, t.glyphStyle)
        assertEquals(CanonicalButton.BTN_SOUTH, t.menuConfirm)
    }

    @Test fun `import carries buildModel into match rule`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Pad", vendorId = 8224, productId = 273,
            buttonBindings = mapOf("b_btn" to 96),
            buildModel = "AYN Thor",
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals("AYN Thor", t.match.androidBuildModel)
    }

    @Test fun `explicit cfg glyph style wins over LegendResolver`() {
        val entry = RetroArchCfgEntry(
            deviceName = "Pad", vendorId = 8224, productId = 273,
            buttonBindings = mapOf("b_btn" to 96, "a_btn" to 97),
            glyphStyle = "SHAPES",
        )
        val thorDevice = ConnectedDevice(
            androidDeviceId = 2, descriptor = "thor", name = "Pad",
            vendorId = 8224, productId = 273, androidBuildModel = "AYN Thor",
            sourceMask = 0, connectedAtMillis = 0,
        )
        val t = RetroArchAutoconfigImporter.import(entry, thorDevice)
        assertEquals(GlyphStyle.SHAPES, t.glyphStyle)
    }

    @Test fun `cannoli keys override hints and mark user mappings`() {
        val entry = RetroArchCfgParser.parse(
            """
            input_device = "Pad"
            input_b_btn = "96"
            input_device_display_name = "My Pad"
            cannoli_user = "true"
            cannoli_confirm_button = "BTN_SOUTH"
            cannoli_exclude_from_gameplay = "true"
            """.trimIndent(),
            fileName = "Pad.cfg",
        )
        val imported = RetroArchAutoconfigImporter.import(entry, device)
        assertEquals("Pad", imported.id)
        assertEquals("My Pad", imported.displayName)
        assertEquals(CanonicalButton.BTN_SOUTH, imported.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, imported.menuBack)
        assertTrue(imported.excludeFromGameplay)
        assertTrue(imported.userEdited)
        assertEquals(MappingSource.USER_WIZARD, imported.source)
    }
}
