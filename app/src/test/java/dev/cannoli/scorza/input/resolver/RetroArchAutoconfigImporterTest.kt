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
import org.junit.Assert.assertNotNull
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
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = emptyMap(),
            axisBindings = mapOf("l2_axis" to AxisRef(axis = 17, direction = +1)),
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
    fun stick_axes_collapse_to_analog_roles_per_canonical_button() {
        val entry = RetroArchCfgEntry(
            deviceName = "Stadia Controller", vendorId = 6353, productId = 37888,
            buttonBindings = emptyMap(),
            axisBindings = mapOf(
                "l_x_plus_axis" to AxisRef(0, +1),
                "l_x_minus_axis" to AxisRef(0, -1),
                "l_y_plus_axis" to AxisRef(1, +1),
                "l_y_minus_axis" to AxisRef(1, -1),
            ),
        )
        val t = RetroArchAutoconfigImporter.import(entry, device)
        // Stick X is bound under BTN_L3 with role LEFT_STICK_X.
        val lxBinding = t.bindings[CanonicalButton.BTN_L3]?.firstOrNull { it is InputBinding.Axis }
        assertNotNull(lxBinding)
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
