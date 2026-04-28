package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.repo.TemplateRepository
import dev.cannoli.scorza.input.v2.resolver.TemplateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun makeResolver(): TemplateResolver {
        val repo = TemplateRepository(tempFolder.root)
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            ),
        )
        return TemplateResolver(repo, ra, tempFolder.root)
    }

    private fun makeBridge(
        resolver: TemplateResolver = makeResolver(),
        portRouter: PortRouter = PortRouter(),
        activeTemplateHolder: ActiveTemplateHolder = ActiveTemplateHolder(),
        clock: () -> Long = { 1_000L },
        buildModel: String = "Pixel",
    ): ControllerV2Bridge = ControllerV2Bridge(
        resolver = resolver,
        portRouter = portRouter,
        activeTemplateHolder = activeTemplateHolder,
        clock = clock,
        buildModel = buildModel,
    )

    @Test
    fun connect_real_controller_routes_through_resolver_router_active_holder() {
        val portRouter = PortRouter()
        val active = ActiveTemplateHolder()
        val bridge = makeBridge(portRouter = portRouter, activeTemplateHolder = active)

        bridge.handleDeviceAdded(stadiaFacts)
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
        assertNotNull(active.active.value)
        assertEquals("Stadia Controller", active.active.value?.match?.name)
    }

    @Test
    fun connect_non_gamepad_device_is_ignored() {
        val portRouter = PortRouter()
        val active = ActiveTemplateHolder()
        val bridge = makeBridge(portRouter = portRouter, activeTemplateHolder = active)

        bridge.handleDeviceAdded(mouseFacts)

        assertNull(portRouter.portFor(mouseFacts.androidDeviceId))
        assertNull(active.active.value)
    }

    @Test
    fun connect_with_zero_vendor_and_product_is_ignored() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.handleDeviceAdded(
            stadiaFacts.copy(vendorId = 0, productId = 0)
        )

        assertNull(portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun disconnect_releases_port() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.handleDeviceAdded(stadiaFacts)
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)
        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))

        bridge.handleDeviceRemoved(stadiaFacts.androidDeviceId)
        assertNull(portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun reconnect_with_same_id_does_nothing_extra() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.handleDeviceAdded(stadiaFacts)
        bridge.handleDeviceAdded(stadiaFacts) // duplicate add, should be a no-op
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun connected_at_millis_uses_clock_for_each_add() {
        var ticks = 1_000L
        val portRouter = PortRouter()
        val bridge = ControllerV2Bridge(
            resolver = makeResolver(),
            portRouter = portRouter,
            activeTemplateHolder = ActiveTemplateHolder(),
            clock = { ticks },
            buildModel = "Pixel",
        )
        bridge.handleDeviceAdded(stadiaFacts)
        ticks = 2_000L
        bridge.handleDeviceAdded(stadiaFacts.copy(androidDeviceId = 9, descriptor = "stadia-2"))

        // Connect order is asserted via PortRouter: device 7 connected first, device 9 second.
        bridge.markLaunchTrigger(7)
        assertEquals(0, portRouter.portFor(7))
        assertEquals(1, portRouter.portFor(9))
    }
}
