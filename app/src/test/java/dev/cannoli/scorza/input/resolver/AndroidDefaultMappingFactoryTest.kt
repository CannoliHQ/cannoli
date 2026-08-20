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
        val t = AndroidDefaultMappingFactory().create(device)
        assertEquals(MappingSource.ANDROID_DEFAULT, t.source)
        assertEquals("Unknown Pad", t.displayName)
        assertTrue(t.id.startsWith("android_default_"))
    }

    @Test
    fun face_buttons_are_bound_to_standard_keycodes() {
        val t = AndroidDefaultMappingFactory().create(device)
        assertEquals(InputBinding.Button(96), t.bindings[CanonicalButton.BTN_SOUTH]!![0])
        assertEquals(InputBinding.Button(97), t.bindings[CanonicalButton.BTN_EAST]!![0])
        assertEquals(InputBinding.Button(99), t.bindings[CanonicalButton.BTN_WEST]!![0])
        assertEquals(InputBinding.Button(100), t.bindings[CanonicalButton.BTN_NORTH]!![0])
    }

    @Test fun `default mapping ignores build model and stays standard redmond`() {
        val device = ConnectedDevice(
            androidDeviceId = 7,
            descriptor = "abc",
            name = "AYN Thor",
            vendorId = 0x2020,
            productId = 0x0111,
            androidBuildModel = "AYN Thor",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )
        val m = AndroidDefaultMappingFactory().create(device)
        assertTrue(m.bindings[CanonicalButton.BTN_SOUTH]!!.any { it is InputBinding.Button && it.keyCode == 96 })
        assertTrue(m.bindings[CanonicalButton.BTN_EAST]!!.any { it is InputBinding.Button && it.keyCode == 97 })
        assertEquals(GlyphStyle.REDMOND, m.glyphStyle)
        assertEquals(CanonicalButton.BTN_SOUTH, m.menuConfirm)
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
        val m = AndroidDefaultMappingFactory().create(device)
        assertTrue(m.bindings[CanonicalButton.BTN_SOUTH]!!.any { it is InputBinding.Button && it.keyCode == 96 })
        assertEquals(GlyphStyle.REDMOND, m.glyphStyle)
        assertEquals(CanonicalButton.BTN_SOUTH, m.menuConfirm)
    }

    @Test
    fun shoulders_triggers_thumbs_start_select_dpad_are_all_bound() {
        val t = AndroidDefaultMappingFactory().create(device)
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
        val t = AndroidDefaultMappingFactory().create(device)
        val menu = t.bindings[CanonicalButton.BTN_MENU].orEmpty()
        val keys = menu.filterIsInstance<dev.cannoli.scorza.input.InputBinding.Button>().map { it.keyCode }
        assertTrue(4 in keys)
        assertTrue(110 in keys)
    }

    @Test
    fun match_rule_carries_the_device_identity() {
        val t = AndroidDefaultMappingFactory().create(device)
        assertEquals("Unknown Pad", t.match.name)
        assertEquals(1, t.match.vendorId)
        assertEquals(2, t.match.productId)
    }

    @Test fun `match rule records a built-in device as built in`() {
        val builtIn = device.copy(isBuiltIn = true)
        val t = AndroidDefaultMappingFactory().create(builtIn)
        assertEquals(true, t.match.builtin)
    }

    @Test fun `match rule records an external device as not built in`() {
        val t = AndroidDefaultMappingFactory().create(device)
        assertEquals(false, t.match.builtin)
    }

    @Test fun `a name that slugifies to empty falls back to the vendor product id`() {
        val punctuationOnly = device.copy(name = "!!!")
        val t = AndroidDefaultMappingFactory().create(punctuationOnly)
        assertEquals("android_default_1_2_${punctuationOnly.name.hashCode()}", t.id)
    }
}
