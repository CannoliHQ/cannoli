package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDefaultTemplateFactoryTest {

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
        val t = AndroidDefaultTemplateFactory.create(device)
        assertEquals(TemplateSource.ANDROID_DEFAULT, t.source)
        assertEquals("Unknown Pad", t.displayName)
        assertTrue(t.id.startsWith("android_default_"))
    }

    @Test
    fun face_buttons_are_bound_to_standard_keycodes() {
        val t = AndroidDefaultTemplateFactory.create(device)
        assertEquals(InputBinding.Button(96), t.bindings[CanonicalButton.BTN_SOUTH]!![0])
        assertEquals(InputBinding.Button(97), t.bindings[CanonicalButton.BTN_EAST]!![0])
        assertEquals(InputBinding.Button(99), t.bindings[CanonicalButton.BTN_WEST]!![0])
        assertEquals(InputBinding.Button(100), t.bindings[CanonicalButton.BTN_NORTH]!![0])
    }

    @Test
    fun shoulders_triggers_thumbs_start_select_dpad_are_all_bound() {
        val t = AndroidDefaultTemplateFactory.create(device)
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
    fun btn_menu_is_unbound() {
        val t = AndroidDefaultTemplateFactory.create(device)
        assertTrue(!t.bindings.containsKey(CanonicalButton.BTN_MENU))
    }

    @Test
    fun match_rule_carries_the_device_identity() {
        val t = AndroidDefaultTemplateFactory.create(device)
        assertEquals("Unknown Pad", t.match.name)
        assertEquals(1, t.match.vendorId)
        assertEquals(2, t.match.productId)
    }
}
