package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
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

    private fun makeBridge(
        resolver: MappingResolver = makeResolver(),
        portRouter: PortRouter = PortRouter(),
        activeMappingHolder: ActiveMappingHolder = ActiveMappingHolder(),
        clock: () -> Long = { 1_000L },
        buildModel: String = "Pixel",
    ): ControllerV2Bridge = ControllerV2Bridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
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
            clock = { ticks },
            buildModel = "Pixel",
        )
        // Non-adjacent device IDs ensure SiblingFolder treats them as separate clusters.
        val second = stadiaFacts.copy(androidDeviceId = 12, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts))
        ticks = 2_000L
        bridge.settleSyncForTest(listOf(stadiaFacts, second))

        bridge.markLaunchTrigger(7)
        assertEquals(0, portRouter.portFor(7))
        assertEquals(1, portRouter.portFor(12))
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

        val second = stadiaFacts.copy(androidDeviceId = 12, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts, second))
        assertEquals(listOf(12), added)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertEquals(listOf(12), removed)
    }

    @Test
    fun retroid_phantom_endpoints_fold_to_single_port_with_sibling_descriptor() {
        // Mirrors the DualSense-on-Retroid case: gamepad endpoint has Retroid vid/pid and empty
        // descriptor (post-folding it should carry the sibling's stable descriptor); siblings have
        // real Sony vid/pid + populated descriptor (MAC-derived hash).
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        val motion = ControllerV2Bridge.DeviceFacts(
            androidDeviceId = 10,
            descriptor = "ds-motion-mac-A",
            name = "DualSense Wireless Controller Motion Sensors",
            vendorId = 0x054c,
            productId = 0x0ce6,
            sourceMask = 0x0,
        )
        val touchpad = ControllerV2Bridge.DeviceFacts(
            androidDeviceId = 11,
            descriptor = "ds-touch-mac-A",
            name = "DualSense Wireless Controller Touchpad",
            vendorId = 0x054c,
            productId = 0x0ce6,
            sourceMask = 0x2002,
        )
        val gamepad = ControllerV2Bridge.DeviceFacts(
            androidDeviceId = 12,
            descriptor = "",
            name = "DualSense Wireless Controller",
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerV2Bridge.SOURCE_GAMEPAD,
        )

        bridge.settleSyncForTest(listOf(motion, touchpad, gamepad))
        bridge.markLaunchTrigger(12)

        // Only the gamepad endpoint gets a port; the siblings alias onto it.
        assertEquals(0, portRouter.portFor(12))
        assertEquals(0, portRouter.portFor(11))
        assertEquals(0, portRouter.portFor(10))
        // The persisted mapping carries the sibling's descriptor so the file is unique per pad.
        val saved = active.active.value
        assertNotNull(saved)
        val savedDescriptor = saved?.match?.descriptor
        assertTrue("expected sibling descriptor, got '$savedDescriptor'",
            savedDescriptor == "ds-motion-mac-A" || savedDescriptor == "ds-touch-mac-A")
    }

    @Test
    fun two_same_model_phantom_clusters_get_separate_ports() {
        // Two DualSenses on Retroid: ids {10,11,12} and {13,14,15}. ID-adjacency keeps clusters
        // separated even though all six InputDevices share name prefix "DualSense Wireless Controller".
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        val padA = listOf(
            ControllerV2Bridge.DeviceFacts(10, "ds-A-motion", "DualSense Wireless Controller Motion Sensors", 0x054c, 0x0ce6, 0x0),
            ControllerV2Bridge.DeviceFacts(11, "ds-A-touch", "DualSense Wireless Controller Touchpad", 0x054c, 0x0ce6, 0x2002),
            ControllerV2Bridge.DeviceFacts(12, "", "DualSense Wireless Controller", 8226, 12289, ControllerV2Bridge.SOURCE_GAMEPAD),
        )
        val padB = listOf(
            ControllerV2Bridge.DeviceFacts(13, "ds-B-motion", "DualSense Wireless Controller Motion Sensors", 0x054c, 0x0ce6, 0x0),
            ControllerV2Bridge.DeviceFacts(14, "ds-B-touch", "DualSense Wireless Controller Touchpad", 0x054c, 0x0ce6, 0x2002),
            ControllerV2Bridge.DeviceFacts(15, "", "DualSense Wireless Controller", 8226, 12289, ControllerV2Bridge.SOURCE_GAMEPAD),
        )

        bridge.settleSyncForTest(padA + padB)
        bridge.markLaunchTrigger(12)

        assertEquals(0, portRouter.portFor(12))
        assertEquals(1, portRouter.portFor(15))
        // Siblings of pad A route to pad A's gamepad.
        assertEquals(0, portRouter.portFor(10))
        assertEquals(0, portRouter.portFor(11))
        // Siblings of pad B route to pad B's gamepad.
        assertEquals(1, portRouter.portFor(13))
        assertEquals(1, portRouter.portFor(14))
    }
}
