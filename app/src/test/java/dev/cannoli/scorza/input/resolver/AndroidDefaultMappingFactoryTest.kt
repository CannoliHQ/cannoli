package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDefaultMappingFactoryTest {

    private val defaultHints = dev.cannoli.scorza.input.hints.ControllerHintTable.fromJson(
        """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
    )

    private val device = ConnectedDevice(
        androidDeviceId = 7,
        descriptor = "abc",
        name = "Unknown Pad",
        vendorId = 1,
        productId = 2,
        androidBuildModel = "Pixel",
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    @Test
    fun template_id_is_derived_from_device_name_and_marked_runtime() {
        val t = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertEquals(MappingSource.ANDROID_DEFAULT, t.source)
        assertEquals("Unknown Pad", t.displayName)
        assertTrue(t.id.startsWith("android_default_"))
    }

    @Test
    fun face_buttons_are_bound_to_standard_keycodes() {
        val t = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertEquals(InputBinding.Button(96), t.bindings[CanonicalButton.BTN_SOUTH]!![0])
        assertEquals(InputBinding.Button(97), t.bindings[CanonicalButton.BTN_EAST]!![0])
        assertEquals(InputBinding.Button(99), t.bindings[CanonicalButton.BTN_WEST]!![0])
        assertEquals(InputBinding.Button(100), t.bindings[CanonicalButton.BTN_NORTH]!![0])
    }

    @Test fun `default mapping for AYN Thor uses nintendo face bindings and plumber`() {
        val device = ConnectedDevice(
            androidDeviceId = 7,
            descriptor = "abc",
            name = "AYN Thor",
            vendorId = 0x2020,
            productId = 0x0111,
            androidBuildModel = "AYN_Thor",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        val m = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertTrue(m.bindings[CanonicalButton.BTN_SOUTH]!!.any { it is InputBinding.Button && it.keyCode == 97 })
        assertTrue(m.bindings[CanonicalButton.BTN_EAST]!!.any { it is InputBinding.Button && it.keyCode == 96 })
        assertEquals(GlyphStyle.PLUMBER, m.glyphStyle)
        assertEquals(CanonicalButton.BTN_EAST, m.menuConfirm)
    }

    @Test fun `default mapping for unknown pad stays standard redmond`() {
        val device = ConnectedDevice(
            androidDeviceId = 7,
            descriptor = "abc",
            name = "Phone",
            vendorId = 0x1234,
            productId = 0x5678,
            androidBuildModel = "Phone",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        val m = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertTrue(m.bindings[CanonicalButton.BTN_SOUTH]!!.any { it is InputBinding.Button && it.keyCode == 96 })
        assertEquals(GlyphStyle.REDMOND, m.glyphStyle)
        assertEquals(CanonicalButton.BTN_SOUTH, m.menuConfirm)
    }

    @Test
    fun shoulders_triggers_thumbs_start_select_dpad_are_all_bound() {
        val t = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertEquals(InputBinding.Button(102), t.bindings[CanonicalButton.BTN_L]!![0])
        assertEquals(InputBinding.Button(103), t.bindings[CanonicalButton.BTN_R]!![0])
        assertEquals(InputBinding.Button(104), t.bindings[CanonicalButton.BTN_L2]!![0])
        assertEquals(InputBinding.Button(105), t.bindings[CanonicalButton.BTN_R2]!![0])
        assertEquals(InputBinding.Button(106), t.bindings[CanonicalButton.BTN_L3]!![0])
        assertEquals(InputBinding.Button(107), t.bindings[CanonicalButton.BTN_R3]!![0])
        assertEquals(InputBinding.Button(108), t.bindings[CanonicalButton.BTN_START]!![0])
        assertEquals(InputBinding.Button(109), t.bindings[CanonicalButton.BTN_SELECT]!![0])
        assertEquals(InputBinding.Button(19), t.bindings[CanonicalButton.BTN_UP]!![0])
        assertEquals(InputBinding.Button(20), t.bindings[CanonicalButton.BTN_DOWN]!![0])
        assertEquals(InputBinding.Button(21), t.bindings[CanonicalButton.BTN_LEFT]!![0])
        assertEquals(InputBinding.Button(22), t.bindings[CanonicalButton.BTN_RIGHT]!![0])
    }

    @Test
    fun btn_menu_defaults_to_back_and_mode_keycodes() {
        val t = AndroidDefaultMappingFactory().create(device, defaultHints)
        val menu = t.bindings[CanonicalButton.BTN_MENU].orEmpty()
        val keys = menu.filterIsInstance<dev.cannoli.scorza.input.InputBinding.Button>().map { it.keyCode }
        assertTrue(4 in keys)
        assertTrue(110 in keys)
    }

    @Test
    fun match_rule_carries_the_device_identity() {
        val t = AndroidDefaultMappingFactory().create(device, defaultHints)
        assertEquals("Unknown Pad", t.match.name)
        assertEquals(1, t.match.vendorId)
        assertEquals(2, t.match.productId)
    }
}
