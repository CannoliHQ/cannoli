package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.repo.MappingRepository
import dev.cannoli.scorza.input.v2.resolver.MappingResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerV2BridgeTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val stadiaFacts = ControllerV2Bridge.DeviceFacts(
        androidDeviceId = 7,
        descriptor = "stadia-1",
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        sourceMask = ControllerV2Bridge.SOURCE_GAMEPAD,
    )

    private val mouseFacts = ControllerV2Bridge.DeviceFacts(
        androidDeviceId = 8,
        descriptor = "mouse-1",
        name = "USB Mouse",
        vendorId = 0x1234,
        productId = 0x5678,
        sourceMask = 0x2002,
    )

    private fun makeResolver(): MappingResolver {
        val repo = MappingRepository(tempFolder.root)
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            ),
        )
        val hints = dev.cannoli.scorza.input.v2.hints.ControllerHintTable.fromJson(
            """{"default":{"menuConfirm":"BTN_EAST","glyphStyle":"PLUMBER"}}"""
        )
        return MappingResolver(repo, ra, hints, tempFolder.root)
    }

    private class StubPhysicalIdentityResolver(
        private val byKey: (ConnectedDevice) -> PhysicalIdentity? = { null },
    ) : PhysicalIdentityResolver {
        override fun identify(device: ConnectedDevice): PhysicalIdentity? = byKey(device)
    }

    private fun wiredFromDevice(device: ConnectedDevice): PhysicalIdentity? {
        if (device.vendorId != 0 && device.productId != 0 && device.descriptor.isNotEmpty()) {
            return PhysicalIdentity.Wired(device.vendorId, device.productId, device.descriptor)
        }
        return null
    }

    private fun makeBridge(
        resolver: MappingResolver = makeResolver(),
        portRouter: PortRouter = PortRouter(),
        activeMappingHolder: ActiveMappingHolder = ActiveMappingHolder(),
        physicalIdentityResolver: PhysicalIdentityResolver = StubPhysicalIdentityResolver { wiredFromDevice(it) },
        clock: () -> Long = { 1_000L },
        buildModel: String = "Pixel",
    ): ControllerV2Bridge = ControllerV2Bridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        physicalIdentityResolver = physicalIdentityResolver,
        clock = clock,
        buildModel = buildModel,
    )

    @Test
    fun connect_real_controller_routes_through_resolver_router_active_holder() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
        assertNotNull(active.active.value)
        assertEquals("Stadia Controller", active.active.value?.match?.name)
    }

    @Test
    fun connect_non_gamepad_device_is_ignored() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        bridge.settleSyncForTest(listOf(mouseFacts))

        assertNull(portRouter.portFor(mouseFacts.androidDeviceId))
        assertNull(active.active.value)
    }

    @Test
    fun connect_with_zero_vendor_and_product_and_empty_name_is_ignored() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(
            listOf(stadiaFacts.copy(vendorId = 0, productId = 0, name = ""))
        )

        assertNull(portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun built_in_handheld_with_zero_vid_pid_is_accepted_and_marked_builtin() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)
        val builtin = ControllerV2Bridge.DeviceFacts(
            androidDeviceId = 1001,
            descriptor = "builtin-1",
            name = "RP4PRO-keypad",
            vendorId = 0,
            productId = 0,
            sourceMask = ControllerV2Bridge.SOURCE_GAMEPAD,
        )
        bridge.settleSyncForTest(listOf(builtin))
        bridge.markLaunchTrigger(1001)
        assertEquals(0, portRouter.portFor(1001))
        assertNotNull(active.active.value)
    }

    @Test
    fun device_with_zero_vid_pid_and_empty_name_is_still_rejected() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        val degenerate = ControllerV2Bridge.DeviceFacts(
            androidDeviceId = 5,
            descriptor = "ghost",
            name = "",
            vendorId = 0,
            productId = 0,
            sourceMask = ControllerV2Bridge.SOURCE_GAMEPAD,
        )
        bridge.settleSyncForTest(listOf(degenerate))
        assertNull(portRouter.portFor(5))
    }

    @Test
    fun disconnect_releases_port() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)
        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))

        bridge.settleSyncForTest(emptyList())
        assertNull(portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun reconnect_with_same_id_does_nothing_extra() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        bridge.settleSyncForTest(listOf(stadiaFacts))
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun two_distinct_controllers_get_separate_ports() {
        var ticks = 1_000L
        val portRouter = PortRouter()
        val bridge = ControllerV2Bridge(
            resolver = makeResolver(),
            portRouter = portRouter,
            activeMappingHolder = ActiveMappingHolder(),
            physicalIdentityResolver = StubPhysicalIdentityResolver { wiredFromDevice(it) },
            clock = { ticks },
            buildModel = "Pixel",
        )
        val second = stadiaFacts.copy(androidDeviceId = 9, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts))
        ticks = 2_000L
        bridge.settleSyncForTest(listOf(stadiaFacts, second))

        bridge.markLaunchTrigger(7)
        assertEquals(0, portRouter.portFor(7))
        assertEquals(1, portRouter.portFor(9))
    }

    @Test
    fun device_added_and_removed_callbacks_fire_only_after_initial_enumeration() {
        val added = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val bridge = makeBridge()
        bridge.onDeviceAdded = { d -> added.add(d.androidDeviceId) }
        bridge.onDeviceRemoved = { departed -> removed.add(departed.androidDeviceId) }

        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertTrue(added.isEmpty())

        val second = stadiaFacts.copy(androidDeviceId = 2, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts, second))
        assertEquals(listOf(2), added)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertEquals(listOf(2), removed)
    }
}
