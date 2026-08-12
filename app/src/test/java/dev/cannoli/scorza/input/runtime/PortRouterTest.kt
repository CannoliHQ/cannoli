package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun template(excluded: Boolean = false) = DeviceMapping(
        id = "t",
        displayName = "T",
        match = DeviceMatchRule(),
        bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
        excludeFromGameplay = excluded,
        source = MappingSource.RETROARCH_AUTOCONFIG,
    )

    private fun PortRouter.connectAndActivate(d: ConnectedDevice, m: DeviceMapping) {
        onConnect(d, m)
        activate(d.androidDeviceId, d.connectedAtMillis)
    }

    @Test
    fun launcher_press_assigns_device_to_port_zero() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        assertEquals(0, r.portFor(1))
    }

    @Test
    fun second_connect_takes_next_free_port() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.connectAndActivate(device(2, 1), template())
        assertEquals(0, r.portFor(1))
        assertEquals(1, r.portFor(2))
    }

    @Test
    fun built_in_is_unassigned_when_external_launched_game() {
        val r = PortRouter()
        r.connectAndActivate(device(99, 0, builtIn = true), template())
        r.connectAndActivate(device(1, 1), template())
        r.markLaunchTrigger(1)
        assertEquals(0, r.portFor(1))
        assertNull(r.portFor(99))
    }

    @Test
    fun built_in_keeps_port_when_no_external_present() {
        val r = PortRouter()
        r.connectAndActivate(device(99, 0, builtIn = true), template())
        r.markLaunchTrigger(99)
        assertEquals(0, r.portFor(99))
    }

    @Test
    fun excluded_template_never_receives_a_port() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template(excluded = true))
        r.markLaunchTrigger(1)
        assertNull(r.portFor(1))
    }

    @Test
    fun disconnect_releases_the_port() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.onDisconnect(1)
        assertNull(r.portFor(1))
    }

    @Test
    fun reassign_swaps_two_devices() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.connectAndActivate(device(2, 1), template())
        r.reassign(deviceId = 1, toPort = 1)
        assertEquals(1, r.portFor(1))
        assertEquals(0, r.portFor(2))
    }

    @Test
    fun hot_join_after_full_takes_next_free_port_in_connect_order() {
        val r = PortRouter(maxPorts = 4)
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.connectAndActivate(device(2, 1), template())
        r.connectAndActivate(device(3, 2), template())
        // device 4 connects after; expect port 3.
        r.connectAndActivate(device(4, 3), template())
        assertEquals(3, r.portFor(4))
    }

    @Test
    fun routing_beyond_maxports_yields_no_port_for_overflow() {
        val r = PortRouter(maxPorts = 2)
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        r.connectAndActivate(device(2, 1), template())
        r.connectAndActivate(device(3, 2), template())
        assertNull(r.portFor(3))
    }

    @Test
    fun connect_without_activation_yields_no_port() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        assertNull(r.portFor(1))
        assertFalse(r.isActivated(1))
        assertTrue(r.routes.value.isEmpty())
    }

    @Test
    fun activate_assigns_port_and_flips_state() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        assertTrue(r.activate(1, 10L))
        assertEquals(0, r.portFor(1))
        assertTrue(r.isActivated(1))
    }

    @Test
    fun activation_order_decides_port_assignment() {
        val r = PortRouter()
        r.onConnect(device(1, 1L), template())
        r.onConnect(device(2, 2L), template())
        // device 2 activates first
        r.activate(2, 10L)
        r.activate(1, 20L)
        assertEquals(0, r.portFor(2))
        assertEquals(1, r.portFor(1))
    }

    @Test
    fun pending_external_does_not_displace_active_internal() {
        val r = PortRouter()
        r.connectAndActivate(device(99, 0, builtIn = true), template())
        // External enrolls but does not activate; internal keeps P1.
        r.onConnect(device(1, 1), template())
        assertEquals(0, r.portFor(99))
        assertNull(r.portFor(1))
    }

    @Test
    fun disconnect_pending_does_not_fire_listener() {
        val r = PortRouter()
        var fires = 0
        r.onActivatedListener = { fires++ }
        r.onConnect(device(1, 0), template())
        r.onDisconnect(1)
        assertEquals(0, fires)
    }

    @Test
    fun reassign_rejects_pending_entry() {
        val r = PortRouter()
        r.connectAndActivate(device(1, 0), template())
        r.onConnect(device(2, 1), template()) // pending
        r.reassign(deviceId = 2, toPort = 0)
        assertEquals(0, r.portFor(1))
        assertNull(r.portFor(2))
    }

    @Test
    fun activate_listener_sees_assigned_port_inside_callback() {
        // Regression: listener must run after recompute so portFor() returns the new port.
        val r = PortRouter()
        var seenPort: Int? = -1
        r.onActivatedListener = { device -> seenPort = r.portFor(device.androidDeviceId) }
        r.onConnect(device(1, 0), template())
        r.activate(1, 5L)
        assertEquals(0, seenPort)
    }

    @Test
    fun activate_listener_fires_once_per_device() {
        val r = PortRouter()
        var fires = 0
        r.onActivatedListener = { fires++ }
        r.onConnect(device(1, 0), template())
        assertTrue(r.activate(1, 5L))
        assertFalse(r.activate(1, 10L))
        assertEquals(1, fires)
    }

    @Test
    fun activate_unknown_device_returns_false() {
        val r = PortRouter()
        assertFalse(r.activate(42, 0L))
    }

    @Test
    fun activate_via_alias_promotes_primary() {
        val r = PortRouter()
        r.onConnect(device(1, 0), template())
        r.addAlias(primaryId = 1, aliasId = 2)
        assertTrue(r.activate(2, 5L))
        assertTrue(r.isActivated(1))
        assertEquals(0, r.portFor(1))
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
        r.connectAndActivate(device(1, 0), template())
        r.markLaunchTrigger(1)
        // The default template fixture only binds BTN_SOUTH to keycode 96.
        val ev = r.evaluatorFor(1)!!
        ev.evaluateKeyDown(96, isAndroidRepeat = false)
        assertEquals(true, r.isCanonicalPressedAt(0, CanonicalButton.BTN_SOUTH))
        assertEquals(false, r.isCanonicalPressedAt(0, CanonicalButton.BTN_EAST))
    }

    @Test
    fun mappingForPort_returns_template_assigned_to_that_port() {
        val r = PortRouter()
        val tmpl = template()
        r.connectAndActivate(device(1, 0), tmpl)
        r.markLaunchTrigger(1)
        org.junit.Assert.assertEquals(tmpl, r.mappingForPort(0))
        org.junit.Assert.assertNull(r.mappingForPort(1))
    }

    @Test
    fun templateFor_returns_template_for_connected_device() {
        val r = PortRouter()
        val tmpl = template()
        r.onConnect(device(1, 0), tmpl)
        org.junit.Assert.assertEquals(tmpl, r.mappingFor(1))
        org.junit.Assert.assertNull(r.mappingFor(99))
    }

    @Test
    fun analogValueAt_returns_last_emitted_normalized_value() {
        val r = PortRouter()
        val analogTemplate = DeviceMapping(
            id = "t",
            displayName = "T",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_L3 to listOf(InputBinding.Axis(
                    axis = 0, restingValue = 0f, activeMin = -1f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.ANALOG_STICK,
                )),
            ),
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        r.connectAndActivate(device(1, 0), analogTemplate)
        r.markLaunchTrigger(1)
        r.evaluatorFor(1)!!.evaluateAxis(mapOf(0 to 0.7f))
        assertEquals(0.7f, r.analogValueAt(0, CanonicalButton.BTN_L3), 0.001f)
    }
}
