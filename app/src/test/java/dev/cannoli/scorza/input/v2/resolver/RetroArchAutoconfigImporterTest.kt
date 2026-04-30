package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.AxisRef
import dev.cannoli.scorza.input.autoconfig.CfgHatDirection
import dev.cannoli.scorza.input.autoconfig.HatRef
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.GlyphStyle
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import dev.cannoli.scorza.input.v2.hints.ControllerHintTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RetroArchAutoconfigImporterTest {

    private val defaultHints = ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

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
        val t = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
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
        val t = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
        val l2 = t.bindings[CanonicalButton.BTN_L2]!![0] as InputBinding.Axis
        assertEquals(17, l2.axis)
        // RA encodes +N as resting=-1, active 0..1 (the issue #151 case).
        assertEquals(-1f, l2.restingValue, 0.001f)
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
        val t = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
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
        val t = RetroArchAutoconfigImporter.import(entry, device, defaultHints)
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
        val t = RetroArchAutoconfigImporter.import(entry, device, defaultHints)

        val up = t.bindings[CanonicalButton.BTN_UP]?.firstOrNull()
        val down = t.bindings[CanonicalButton.BTN_DOWN]?.firstOrNull()
        val left = t.bindings[CanonicalButton.BTN_LEFT]?.firstOrNull()
        val right = t.bindings[CanonicalButton.BTN_RIGHT]?.firstOrNull()

        org.junit.Assert.assertTrue(up is InputBinding.Hat && up.axis == 16 && up.direction == HatDirection.UP)
        org.junit.Assert.assertTrue(down is InputBinding.Hat && down.axis == 16 && down.direction == HatDirection.DOWN)
        org.junit.Assert.assertTrue(left is InputBinding.Hat && left.axis == 15 && left.direction == HatDirection.LEFT)
        org.junit.Assert.assertTrue(right is InputBinding.Hat && right.axis == 15 && right.direction == HatDirection.RIGHT)
    }

    @Test fun `importer applies hint for matching VID`() {
        val table = ControllerHintTable.fromJson(
            """{"vid_pid":[{"vendor_id":1356,"menuConfirm":"BTN_SOUTH","glyphStyle":"SHAPES"}],
                "default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
        )
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
        val tpl = RetroArchAutoconfigImporter.import(entry, device, table)
        assertEquals(CanonicalButton.BTN_SOUTH, tpl.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, tpl.menuBack)
        assertEquals(GlyphStyle.SHAPES, tpl.glyphStyle)
    }
}
