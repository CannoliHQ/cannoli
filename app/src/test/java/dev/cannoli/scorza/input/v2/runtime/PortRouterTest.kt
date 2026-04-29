package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortRouterTest {

    private fun device(id: Int, t: Long, builtIn: Boolean = false) =
        ConnectedDevice(
            androidDeviceId = id,
            descriptor = "d$id",
            name = "Pad $id",
            vendorId = 1,
            productId = 1,
            androidBuildModel = "model",
            sourceMask = 0,
            connectedAtMillis = t,
            isBuiltIn = builtIn,
        )

    private fun template(excluded: Boolean = false) = DeviceTemplate(
        id = "t",
        displayName = "T",
        match = DeviceMatchRule(),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
        excludeFromGameplay = excluded,
        source = TemplateSource.RETROARCH_AUTOCONFIG,
    )

    @Test
    fun launcher_press_assigns_device_to_port_zero() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        assertEquals(0, r.portFor(1))
    }

    @Test
    fun second_connect_takes_next_free_port() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onConnect(device(2, 1), template())
        assertEquals(0, r.portFor(1))
        assertEquals(1, r.portFor(2))
    }

    @Test
    fun built_in_is_unassigned_when_external_launched_game() {
        val r = PortRouter()
        r.onConnect(device(99, 0, builtIn = true), template())
        r.onConnect(device(1, 1), template())
        r.markLaunchTrigger(1)
        assertEquals(0, r.portFor(1))
        assertNull(r.portFor(99))
    }

    @Test
    fun built_in_keeps_port_when_no_external_present() {
        val r = PortRouter()
        r.onConnect(device(99, 0, builtIn = true), template())
        r.markLaunchTrigger(99)
        assertEquals(0, r.portFor(99))
    }

    @Test
    fun excluded_template_never_receives_a_port() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template(excluded = true))
        r.markLaunchTrigger(1)
        assertNull(r.portFor(1))
    }

    @Test
    fun disconnect_releases_the_port() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onDisconnect(1)
        assertNull(r.portFor(1))
    }

    @Test
    fun reassign_swaps_two_devices() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onConnect(device(2, 1), template())
        r.reassign(deviceId = 1, toPort = 1)
        assertEquals(1, r.portFor(1))
        assertEquals(0, r.portFor(2))
    }

    @Test
    fun hot_join_after_full_takes_next_free_port_in_connect_order() {
        val r = PortRouter(maxPorts = 4)
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onConnect(device(2, 1), template())
        r.onConnect(device(3, 2), template())
        // device 4 connects after; expect port 3.
        r.onConnect(device(4, 3), template())
        assertEquals(3, r.portFor(4))
    }

    @Test
    fun routing_beyond_maxports_yields_no_port_for_overflow() {
        val r = PortRouter(maxPorts = 2)
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onConnect(device(2, 1), template())
        r.onConnect(device(3, 2), template())
        assertNull(r.portFor(3))
    }

    @Test
    fun evaluator_is_created_on_connect_and_dropped_on_disconnect() {
        val r = PortRouter()
        val device = device(1, 0)
        val tmpl = template()
        r.onConnect(device, tmpl)
        val first = r.evaluatorFor(1)
        org.junit.Assert.assertNotNull(first)
        r.onDisconnect(1)
        org.junit.Assert.assertNull(r.evaluatorFor(1))
    }

    @Test
    fun isCanonicalPressedAt_reflects_current_evaluator_state() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.markLaunchTrigger(1)
        // The default template fixture only binds BTN_SOUTH to keycode 96.
        val ev = r.evaluatorFor(1)!!
        ev.evaluateKeyDown(96, isAndroidRepeat = false)
        assertEquals(true, r.isCanonicalPressedAt(0, CanonicalButton.BTN_SOUTH))
        assertEquals(false, r.isCanonicalPressedAt(0, CanonicalButton.BTN_EAST))
    }

    @Test
    fun templateForPort_returns_template_assigned_to_that_port() {
        val r = PortRouter()
        val tmpl = template()
        r.onConnect(device(1, 0), tmpl)
        r.markLaunchTrigger(1)
        org.junit.Assert.assertEquals(tmpl, r.templateForPort(0))
        org.junit.Assert.assertNull(r.templateForPort(1))
    }

    @Test
    fun templateFor_returns_template_for_connected_device() {
        val r = PortRouter()
        val tmpl = template()
        r.onConnect(device(1, 0), tmpl)
        org.junit.Assert.assertEquals(tmpl, r.templateFor(1))
        org.junit.Assert.assertNull(r.templateFor(99))
    }

    @Test
    fun analogValueAt_returns_last_emitted_normalized_value() {
        val r = PortRouter()
        val analogTemplate = DeviceTemplate(
            id = "t",
            displayName = "T",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_L3 to listOf(InputBinding.Axis(
                    axis = 0, restingValue = 0f, activeMin = -1f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.LEFT_STICK_X,
                )),
            ),
            source = TemplateSource.RETROARCH_AUTOCONFIG,
        )
        r.onConnect(device(1, 0), analogTemplate)
        r.markLaunchTrigger(1)
        r.evaluatorFor(1)!!.evaluateAxis(mapOf(0 to 0.7f))
        assertEquals(0.7f, r.analogValueAt(0, AnalogRole.LEFT_STICK_X), 0.001f)
    }
}
