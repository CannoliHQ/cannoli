package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.resolver.DevKeyboardMapping
import dev.cannoli.scorza.input.resolver.MappingResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerBridgeTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val stadiaFacts = ControllerBridge.DeviceFacts(
        androidDeviceId = 7,
        descriptor = "stadia-1",
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        sourceMask = ControllerBridge.SOURCE_GAMEPAD,
    )

    private val mouseFacts = ControllerBridge.DeviceFacts(
        androidDeviceId = 8,
        descriptor = "mouse-1",
        name = "USB Mouse",
        vendorId = 0x1234,
        productId = 0x5678,
        sourceMask = 0x2002,
    )

    private val autoconfigDir: java.io.File get() = java.io.File(tempFolder.root, "Autoconfig")

    private val autoconfigRepo = AutoconfigRepository { autoconfigDir }

    private fun makeResolver(): MappingResolver {
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "Stadia Controller",
                vendorId = 6353,
                productId = 37888,
                buttonBindings = mapOf("b_btn" to 96),
            ),
        )
        return MappingResolver(
            autoconfigRepo,
            dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries.forTest(ra),
        )
    }

    private val keyboardFacts = ControllerBridge.DeviceFacts(
        androidDeviceId = 4,
        descriptor = "kbd-1",
        name = "AVD Keyboard",
        vendorId = 0,
        productId = 0,
        sourceMask = android.view.InputDevice.SOURCE_KEYBOARD,
        keyboardType = android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC,
    )

    // AVDs enumerate this alongside the host keyboard. It is what `adb shell input` injects under,
    // and its id is negative because Android marks virtual devices with id < 0.
    private val virtualKeyboardFacts = ControllerBridge.DeviceFacts(
        androidDeviceId = ControllerBridge.VIRTUAL_KEYBOARD_ID,
        descriptor = "a718a782d34bc767f4689c232d64d527998ea7fd",
        name = "Virtual",
        vendorId = 0,
        productId = 0,
        sourceMask = android.view.InputDevice.SOURCE_KEYBOARD,
        keyboardType = android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC,
    )

    // The AVD's host keyboard, which really does land on device id 0.
    private val hostKeyboardFacts = ControllerBridge.DeviceFacts(
        androidDeviceId = 0,
        descriptor = "qwerty2-desc",
        name = "qwerty2",
        vendorId = 0,
        productId = 0,
        sourceMask = android.view.InputDevice.SOURCE_KEYBOARD or android.view.InputDevice.SOURCE_DPAD,
        keyboardType = android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC,
    )

    private fun makeBridge(
        resolver: MappingResolver = makeResolver(),
        portRouter: PortRouter = PortRouter(),
        activeMappingHolder: ActiveMappingHolder = ActiveMappingHolder(),
        clock: () -> Long = { 1_000L },
        buildModel: String = "Pixel",
        devKeyboardEnabled: Boolean = false,
    ): ControllerBridge = ControllerBridge(
        resolver = resolver,
        portRouter = portRouter,
        activeMappingHolder = activeMappingHolder,
        autoconfigRepository = autoconfigRepo,
        clock = clock,
        buildModel = buildModel,
        devKeyboardEnabled = devKeyboardEnabled,
    )

    @Test
    fun connect_real_controller_routes_through_resolver_router_active_holder() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        portRouter.activate(stadiaFacts.androidDeviceId, 1L)
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
        assertNotNull(active.active.value)
        assertEquals("Stadia Controller", active.active.value?.match?.name)
    }

    @Test
    fun connected_but_not_activated_has_no_port_and_no_active_mapping() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        bridge.settleSyncForTest(listOf(stadiaFacts))

        assertNull(portRouter.portFor(stadiaFacts.androidDeviceId))
        assertNull(active.active.value)
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
    fun internal_pad_is_tagged_built_in_via_isExternal_false() {
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)
        val builtin = ControllerBridge.DeviceFacts(
            androidDeviceId = 1001,
            descriptor = "builtin-1",
            name = "RP4PRO-keypad",
            vendorId = 0,
            productId = 0,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = false,
        )
        bridge.settleSyncForTest(listOf(builtin))
        portRouter.activate(1001, 1L)
        bridge.markLaunchTrigger(1001)
        assertEquals(0, portRouter.portFor(1001))
        assertNotNull(active.active.value)
        assertTrue(portRouter.snapshotEntries().single { it.androidDeviceId == 1001 }.device.isBuiltIn)
    }

    @Test
    fun internal_pad_with_faked_nonzero_vid_pid_is_still_built_in() {
        // Retroid handhelds fake VID/PID on the internal pad; the old zero-VID/PID heuristic mis-
        // tagged this as external. Trust Android's isExternal flag instead.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        val retroidInternal = ControllerBridge.DeviceFacts(
            androidDeviceId = 8,
            descriptor = "retroid-internal",
            name = "Retroid Pocket Controller",
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = false,
        )
        bridge.settleSyncForTest(listOf(retroidInternal))
        portRouter.activate(8, 1L)
        assertTrue(portRouter.snapshotEntries().single { it.androidDeviceId == 8 }.device.isBuiltIn)
    }

    @Test
    fun internal_pad_detected_via_buildmodel_name_prefix_when_isexternal_lies() {
        // Retroid kernel lies about isExternal (reports true for the internal pad). Fall back to
        // name-vs-Build.MODEL prefix: device name 'Retroid Pocket Controller' matches the brand
        // 'Retroid' from Build.MODEL='Retroid Pocket Classic', so it's still treated as built-in.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, buildModel = "Retroid Pocket Classic")
        val retroidInternal = ControllerBridge.DeviceFacts(
            androidDeviceId = 8,
            descriptor = "retroid-internal",
            name = "Retroid Pocket Controller",
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = true,
        )
        bridge.settleSyncForTest(listOf(retroidInternal))
        portRouter.activate(8, 1L)
        assertTrue(portRouter.snapshotEntries().single { it.androidDeviceId == 8 }.device.isBuiltIn)
    }

    @Test
    fun external_pad_on_same_handheld_is_not_built_in_via_name_heuristic() {
        // The Pro pad on a Retroid still reads as external because its name does not start with
        // the Build.MODEL brand prefix.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, buildModel = "Retroid Pocket Classic")
        val proPad = ControllerBridge.DeviceFacts(
            androidDeviceId = 16,
            descriptor = "pro-pad",
            name = "Nintendo Switch Pro Controller",
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = true,
        )
        bridge.settleSyncForTest(listOf(proPad))
        portRouter.activate(16, 1L)
        assertFalse(portRouter.snapshotEntries().single { it.androidDeviceId == 16 }.device.isBuiltIn)
    }

    @Test
    fun external_pad_with_zero_vid_pid_is_not_built_in() {
        // Inverse: some quirky externals report 0/0; trust isExternal=true over the old heuristic.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        val external = ControllerBridge.DeviceFacts(
            androidDeviceId = 9,
            descriptor = "ext",
            name = "Weird USB Pad",
            vendorId = 0,
            productId = 0,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = true,
        )
        bridge.settleSyncForTest(listOf(external))
        portRouter.activate(9, 1L)
        assertFalse(portRouter.snapshotEntries().single { it.androidDeviceId == 9 }.device.isBuiltIn)
    }

    @Test
    fun device_with_zero_vid_pid_and_empty_name_is_still_rejected() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        val degenerate = ControllerBridge.DeviceFacts(
            androidDeviceId = 5,
            descriptor = "ghost",
            name = "",
            vendorId = 0,
            productId = 0,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
        )
        bridge.settleSyncForTest(listOf(degenerate))
        assertNull(portRouter.portFor(5))
    }

    @Test
    fun disconnect_releases_port() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        portRouter.activate(stadiaFacts.androidDeviceId, 1L)
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
        portRouter.activate(stadiaFacts.androidDeviceId, 1L)
        bridge.markLaunchTrigger(stadiaFacts.androidDeviceId)

        assertEquals(0, portRouter.portFor(stadiaFacts.androidDeviceId))
    }

    @Test
    fun saved_mapping_that_appears_after_first_settle_is_picked_up_on_resettle() {
        // Boot order on a fresh install: the bridge enumerates before MANAGE_EXTERNAL_STORAGE is
        // granted, so the autoconfig directory reads empty and the pad falls back to the bundled
        // cfg. The re-settle that runs once storage is available has to pick up the user's file,
        // which means the settle has to drop the empty listing the store cached before the grant.
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        portRouter.activate(stadiaFacts.androidDeviceId, 1_000L)
        assertEquals(MappingSource.RETROARCH_AUTOCONFIG, portRouter.mappingFor(7)?.source)

        autoconfigDir.mkdirs()
        java.io.File(autoconfigDir, "stadia_user.cfg").writeText(
            """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "190"
            cannoli_user = "true"
            """.trimIndent()
        )
        bridge.settleSyncForTest(listOf(stadiaFacts))

        assertEquals("stadia_user", portRouter.mappingFor(7)?.id)
        assertEquals("stadia_user", active.active.value?.id)
        assertTrue(portRouter.evaluatorFor(7)?.keyCodeIsBound(190) == true)
    }

    @Test
    fun two_distinct_controllers_get_separate_ports() {
        var ticks = 1_000L
        val portRouter = PortRouter()
        val bridge = ControllerBridge(
            resolver = makeResolver(),
            portRouter = portRouter,
            activeMappingHolder = ActiveMappingHolder(),
            autoconfigRepository = autoconfigRepo,
            clock = { ticks },
            buildModel = "Pixel",
        )
        // Non-adjacent device IDs ensure SiblingFolder treats them as separate clusters.
        val second = stadiaFacts.copy(androidDeviceId = 12, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts))
        portRouter.activate(7, 1_000L)
        ticks = 2_000L
        bridge.settleSyncForTest(listOf(stadiaFacts, second))
        portRouter.activate(12, 2_000L)

        bridge.markLaunchTrigger(7)
        assertEquals(0, portRouter.portFor(7))
        assertEquals(1, portRouter.portFor(12))
    }

    @Test
    fun device_added_callback_fires_on_activation_not_enumeration() {
        val added = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        bridge.onDeviceAdded = { d -> added.add(d.androidDeviceId) }
        bridge.onDeviceRemoved = { departed -> removed.add(departed.androidDeviceId) }

        // Enumeration alone never fires onDeviceAdded; the device is still pending.
        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertTrue(added.isEmpty())

        // A hot-plugged pad is also silent until it produces input.
        val second = stadiaFacts.copy(androidDeviceId = 12, descriptor = "stadia-2")
        bridge.settleSyncForTest(listOf(stadiaFacts, second))
        assertTrue(added.isEmpty())

        // Activating either pad fires the callback in activation order.
        portRouter.activate(12, 2_000L)
        portRouter.activate(stadiaFacts.androidDeviceId, 3_000L)
        assertEquals(listOf(12, stadiaFacts.androidDeviceId), added)

        // Removing an activated device still fires onDeviceRemoved.
        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertEquals(listOf(12), removed)
    }

    @Test
    fun activation_writes_nothing_to_the_autoconfig_database() {
        // Only a deliberate user edit authors a cfg. A mapping the bridge derived is cheap to
        // derive again on the next boot, and writing it would leave a file the user never made.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        portRouter.activate(stadiaFacts.androidDeviceId, 1_000L)

        assertTrue(portRouter.isActivated(stadiaFacts.androidDeviceId))
        assertTrue(autoconfigRepo.listEntries().isEmpty())
    }

    @Test
    fun activation_leaves_a_user_authored_cfg_byte_identical() {
        // Mapping ids come from device identity, so anything the bridge wrote back would land on
        // the very file the user authored for that pad.
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)
        autoconfigDir.mkdirs()
        val cfg = java.io.File(autoconfigDir, "stadia_user.cfg")
        val original = """
            input_device = "Stadia Controller"
            input_vendor_id = "6353"
            input_product_id = "37888"
            input_b_btn = "190"
            cannoli_user = "true"
        """.trimIndent()
        cfg.writeText(original)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        assertEquals("stadia_user", portRouter.mappingFor(stadiaFacts.androidDeviceId)?.id)
        portRouter.activate(stadiaFacts.androidDeviceId, 1_000L)

        assertEquals(original, cfg.readText())
        assertEquals(1, autoconfigRepo.listEntries().size)
    }

    @Test
    fun phantom_rewrite_pad_keeps_the_cfg_derived_glyph_not_the_device_reported_vid() {
        // Retroid-family hosts rewrite a paired BT pad's gamepad endpoint to report the built-in's
        // VID/PID while keeping the pad's real name. The bundled cfg still matches by name and
        // carries the pad's true (Sony) VID, so the resolver correctly yields SHAPES. The bridge
        // must enroll that verbatim -- re-deriving a glyph from the device's rewritten VID would
        // silently clobber it back to REDMOND, undoing what the resolver got right.
        val portRouter = PortRouter()
        val ra = listOf(
            RetroArchCfgEntry(
                deviceName = "DualSense Wireless Controller",
                vendorId = 1356,
                productId = 3302,
                buttonBindings = mapOf("a_btn" to 96, "b_btn" to 97),
            ),
        )
        val bundled = dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries.forTest(ra)
        val bridge = ControllerBridge(
            resolver = MappingResolver(autoconfigRepo, bundled),
            portRouter = portRouter,
            activeMappingHolder = ActiveMappingHolder(),
            autoconfigRepository = autoconfigRepo,
            clock = { 1_000L },
            buildModel = "Retroid Pocket Classic",
        )
        val phantomDualsense = ControllerBridge.DeviceFacts(
            androidDeviceId = 30,
            descriptor = "phantom-bt",
            name = "DualSense Wireless Controller",
            // Phantom-rewritten to the Retroid built-in's VID/PID instead of Sony's.
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
        )

        bridge.settleSyncForTest(listOf(phantomDualsense))

        val mapping = portRouter.mappingFor(30)
        assertEquals(dev.cannoli.scorza.input.GlyphStyle.SHAPES, mapping?.glyphStyle)
        assertEquals(dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH, mapping?.menuConfirm)
    }

    @Test
    fun user_edited_mapping_is_enrolled_verbatim() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, buildModel = "AYN Thor")
        val thorPad = ControllerBridge.DeviceFacts(
            androidDeviceId = 21,
            descriptor = "thor-user",
            name = "Odin Controller",
            vendorId = 0x2020,
            productId = 0x0111,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
            isExternal = false,
        )
        autoconfigDir.mkdirs()
        java.io.File(autoconfigDir, "thor_user.cfg").writeText(
            """
            input_device = "Odin Controller"
            input_vendor_id = "8224"
            input_product_id = "273"
            input_b_btn = "96"
            cannoli_confirm_button = "BTN_SOUTH"
            cannoli_glyph_style = "REDMOND"
            cannoli_descriptor = "thor-user"
            cannoli_user = "true"
            """.trimIndent()
        )

        bridge.settleSyncForTest(listOf(thorPad))

        val mapping = portRouter.mappingFor(21)
        assertTrue(mapping?.userEdited == true)
        assertEquals(dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH, mapping?.menuConfirm)
        assertEquals(dev.cannoli.scorza.input.GlyphStyle.REDMOND, mapping?.glyphStyle)
    }

    @Test
    fun enrollment_then_disconnect_writes_nothing() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter)

        bridge.settleSyncForTest(listOf(stadiaFacts))
        bridge.settleSyncForTest(emptyList())

        assertTrue(autoconfigRepo.listEntries().isEmpty())
    }

    @Test
    fun retroid_phantom_endpoints_fold_to_single_port_with_sibling_descriptor() {
        // Mirrors the DualSense-on-Retroid case: gamepad endpoint has Retroid vid/pid and empty
        // descriptor (post-folding it should carry the sibling's stable descriptor); siblings have
        // real Sony vid/pid + populated descriptor (MAC-derived hash).
        val portRouter = PortRouter()
        val active = ActiveMappingHolder()
        val bridge = makeBridge(portRouter = portRouter, activeMappingHolder = active)

        val motion = ControllerBridge.DeviceFacts(
            androidDeviceId = 10,
            descriptor = "ds-motion-mac-A",
            name = "DualSense Wireless Controller Motion Sensors",
            vendorId = 0x054c,
            productId = 0x0ce6,
            sourceMask = 0x0,
        )
        val touchpad = ControllerBridge.DeviceFacts(
            androidDeviceId = 11,
            descriptor = "ds-touch-mac-A",
            name = "DualSense Wireless Controller Touchpad",
            vendorId = 0x054c,
            productId = 0x0ce6,
            sourceMask = 0x2002,
        )
        val gamepad = ControllerBridge.DeviceFacts(
            androidDeviceId = 12,
            descriptor = "",
            name = "DualSense Wireless Controller",
            vendorId = 8226,
            productId = 12289,
            sourceMask = ControllerBridge.SOURCE_GAMEPAD,
        )

        bridge.settleSyncForTest(listOf(motion, touchpad, gamepad))
        portRouter.activate(12, 1_000L)
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
            ControllerBridge.DeviceFacts(10, "ds-A-motion", "DualSense Wireless Controller Motion Sensors", 0x054c, 0x0ce6, 0x0),
            ControllerBridge.DeviceFacts(11, "ds-A-touch", "DualSense Wireless Controller Touchpad", 0x054c, 0x0ce6, 0x2002),
            ControllerBridge.DeviceFacts(12, "", "DualSense Wireless Controller", 8226, 12289, ControllerBridge.SOURCE_GAMEPAD),
        )
        val padB = listOf(
            ControllerBridge.DeviceFacts(13, "ds-B-motion", "DualSense Wireless Controller Motion Sensors", 0x054c, 0x0ce6, 0x0),
            ControllerBridge.DeviceFacts(14, "ds-B-touch", "DualSense Wireless Controller Touchpad", 0x054c, 0x0ce6, 0x2002),
            ControllerBridge.DeviceFacts(15, "", "DualSense Wireless Controller", 8226, 12289, ControllerBridge.SOURCE_GAMEPAD),
        )

        bridge.settleSyncForTest(padA + padB)
        portRouter.activate(12, 1_000L)
        portRouter.activate(15, 2_000L)
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

    @Test
    fun dev_keyboard_disabled_leaves_keyboard_unenrolled() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = false)

        bridge.settleSyncForTest(listOf(keyboardFacts))

        assertNull(portRouter.mappingFor(keyboardFacts.androidDeviceId))
        assertNull(portRouter.mappingFor(ControllerBridge.VIRTUAL_KEYBOARD_ID))
    }

    @Test
    fun dev_keyboard_enabled_enrolls_keyboard_with_keyboard_bindings() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        bridge.settleSyncForTest(listOf(keyboardFacts))

        val mapping = portRouter.mappingFor(keyboardFacts.androidDeviceId)
        assertNotNull(mapping)
        assertEquals(DevKeyboardMapping.ID, mapping?.id)
        // Enter, not the AndroidDefault BTN_A keycode 96, proves the resolver was bypassed.
        assertEquals(
            listOf(dev.cannoli.scorza.input.InputBinding.Button(android.view.KeyEvent.KEYCODE_ENTER)),
            mapping?.bindings?.get(dev.cannoli.scorza.input.CanonicalButton.BTN_SOUTH)?.take(1),
        )
    }

    @Test
    fun dev_keyboard_mapping_is_never_persisted() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        bridge.settleSyncForTest(listOf(keyboardFacts))
        portRouter.activate(keyboardFacts.androidDeviceId, 1_000L)

        assertNull(autoconfigRepo.findById(DevKeyboardMapping.ID))
    }

    @Test
    fun adb_injected_virtual_keyboard_aliases_onto_the_dev_keyboard() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        bridge.settleSyncForTest(listOf(keyboardFacts))

        // Events injected by `adb shell input` arrive under VIRTUAL_KEYBOARD_ID and must resolve
        // to the same evaluator and mapping as the physical keyboard.
        assertEquals(
            DevKeyboardMapping.ID,
            portRouter.mappingFor(ControllerBridge.VIRTUAL_KEYBOARD_ID)?.id,
        )
        assertNotNull(portRouter.evaluatorFor(ControllerBridge.VIRTUAL_KEYBOARD_ID))
        assertTrue(
            portRouter.aliasesFor(keyboardFacts.androidDeviceId)
                .contains(ControllerBridge.VIRTUAL_KEYBOARD_ID)
        )
    }

    @Test
    fun dev_keyboard_falls_back_to_virtual_device_when_no_keyboard_is_attached() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        bridge.settleSyncForTest(listOf(mouseFacts))

        assertEquals(
            DevKeyboardMapping.ID,
            portRouter.mappingFor(ControllerBridge.VIRTUAL_KEYBOARD_ID)?.id,
        )
    }

    @Test
    fun dev_keyboard_lowest_id_wins_when_several_keyboards_are_attached() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)
        val second = keyboardFacts.copy(androidDeviceId = 9, descriptor = "kbd-2", name = "Other Keys")

        bridge.settleSyncForTest(listOf(second, keyboardFacts))

        assertEquals(DevKeyboardMapping.ID, portRouter.mappingFor(4)?.id)
        assertNull(portRouter.mappingFor(9))
    }

    @Test
    fun dev_keyboard_prefers_the_host_keyboard_over_the_virtual_endpoint() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        // Both enumerate on a real AVD. Selecting the numerically lowest id picked 'Virtual' at
        // -1, leaving everything typed on the host keyboard unrouted.
        bridge.settleSyncForTest(listOf(virtualKeyboardFacts, hostKeyboardFacts))

        assertEquals(DevKeyboardMapping.ID, portRouter.mappingFor(0)?.id)
        assertTrue(
            portRouter.aliasesFor(0).contains(ControllerBridge.VIRTUAL_KEYBOARD_ID)
        )
        // Both paths must reach the same evaluator: host typing and `adb shell input`.
        assertEquals(
            DevKeyboardMapping.ID,
            portRouter.mappingFor(ControllerBridge.VIRTUAL_KEYBOARD_ID)?.id,
        )
    }

    @Test
    fun is_dev_keyboard_device_is_true_only_for_the_enrolled_keyboard_and_its_alias() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        bridge.settleSyncForTest(listOf(hostKeyboardFacts, stadiaFacts))

        assertTrue(bridge.isDevKeyboardDevice(hostKeyboardFacts.androidDeviceId))
        assertTrue(bridge.isDevKeyboardDevice(ControllerBridge.VIRTUAL_KEYBOARD_ID))
        assertFalse(bridge.isDevKeyboardDevice(stadiaFacts.androidDeviceId))
    }

    @Test
    fun unenrolled_keyboard_sourced_device_is_not_a_dev_keyboard_device() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)

        // A handheld's GPIO menu button is keyboard-sourced but non-alphabetic, so the bridge
        // never enrolls it. MainActivity's BACK shim keys off this, and must keep treating it as
        // an ordinary device even while the feature is switched on.
        val gpioMenuButton = ControllerBridge.DeviceFacts(
            androidDeviceId = 7,
            descriptor = "gpio-keys-desc",
            name = "gpio-keys",
            vendorId = 0,
            productId = 0,
            sourceMask = android.view.InputDevice.SOURCE_KEYBOARD,
            keyboardType = android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
        )
        bridge.settleSyncForTest(listOf(gpioMenuButton))

        assertFalse(bridge.isDevKeyboardDevice(7))
    }

    @Test
    fun is_dev_keyboard_device_is_false_when_the_feature_is_off() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = false)

        bridge.settleSyncForTest(listOf(hostKeyboardFacts))

        assertFalse(bridge.isDevKeyboardDevice(hostKeyboardFacts.androidDeviceId))
        assertFalse(bridge.isDevKeyboardDevice(ControllerBridge.VIRTUAL_KEYBOARD_ID))
    }

    @Test
    fun gamepad_advertising_keyboard_source_keeps_its_resolved_mapping() {
        val portRouter = PortRouter()
        val bridge = makeBridge(portRouter = portRouter, devKeyboardEnabled = true)
        val padWithKeyboardSource = stadiaFacts.copy(
            sourceMask = ControllerBridge.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_KEYBOARD,
            keyboardType = android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC,
        )

        bridge.settleSyncForTest(listOf(padWithKeyboardSource))

        val mapping = portRouter.mappingFor(stadiaFacts.androidDeviceId)
        assertNotNull(mapping)
        assertFalse(DevKeyboardMapping.ID == mapping?.id)
    }
}
