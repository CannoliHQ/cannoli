package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDispatcherTest {

    private fun westernTemplate() = DeviceMapping(
        id = "western",
        displayName = "Western",
        match = DeviceMatchRule(),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
            CanonicalButton.BTN_UP to listOf(InputBinding.Button(19)),
            CanonicalButton.BTN_SELECT to listOf(InputBinding.Button(109)),
        ),
        menuConfirm = CanonicalButton.BTN_EAST,
        menuBack = CanonicalButton.BTN_SOUTH,
        glyphStyle = GlyphStyle.REDMOND,
        source = MappingSource.RETROARCH_AUTOCONFIG,
    )

    private fun nintendoTemplate() = DeviceMapping(
        id = "nintendo",
        displayName = "Nintendo",
        match = DeviceMatchRule(),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
        glyphStyle = GlyphStyle.PLUMBER,
        source = MappingSource.RETROARCH_AUTOCONFIG,
    )

    private fun device(id: Int) = ConnectedDevice(
        androidDeviceId = id,
        descriptor = "d$id",
        name = "Pad $id",
        vendorId = 1,
        productId = 1,
        androidBuildModel = "M",
        sourceMask = 0,
        connectedAtMillis = id.toLong(),
    )

    private fun setup(template: DeviceMapping, deviceId: Int = 7): Triple<InputDispatcher, PortRouter, ActiveMappingHolder> {
        val router = PortRouter()
        val active = ActiveMappingHolder()
        router.onConnect(device(deviceId), template)
        router.markLaunchTrigger(deviceId)
        val dispatcher = InputDispatcher(router, active)
        return Triple(dispatcher, router, active)
    }

    @Test
    fun btn_east_press_fires_onConfirm_for_western_template() {
        val (d, _, _) = setup(westernTemplate())
        var fired = 0
        d.onConfirm = { fired++ }
        val handled = d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertTrue(handled)
        assertEquals(1, fired)
    }

    @Test
    fun btn_east_press_fires_onBack_for_nintendo_template() {
        val (d, _, _) = setup(nintendoTemplate())
        var back = 0
        var confirm = 0
        d.onBack = { back++ }
        d.onConfirm = { confirm++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, back)
        assertEquals(0, confirm)
    }

    @Test
    fun btn_south_press_fires_onBack_for_western_template() {
        val (d, _, _) = setup(westernTemplate())
        var back = 0
        d.onBack = { back++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, back)
    }

    @Test
    fun btn_south_press_fires_onConfirm_for_nintendo_template() {
        val (d, _, _) = setup(nintendoTemplate())
        var confirm = 0
        d.onConfirm = { confirm++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, confirm)
    }

    @Test
    fun btn_up_press_fires_onUp_regardless_of_template() {
        val (d, _, _) = setup(westernTemplate())
        var up = 0
        d.onUp = { up++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, up)
    }

    @Test
    fun btn_select_press_fires_onSelect_release_fires_onSelectUp() {
        val (d, _, _) = setup(westernTemplate())
        var sel = 0
        var selUp = 0
        d.onSelect = { sel++ }
        d.onSelectUp = { selUp++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 109, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 109, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(1, sel)
        assertEquals(1, selUp)
    }

    @Test
    fun active_template_updates_to_last_pressing_controller() {
        val router = PortRouter()
        val active = ActiveMappingHolder()
        router.onConnect(device(7), westernTemplate())
        router.onConnect(device(8), nintendoTemplate())
        router.markLaunchTrigger(7)
        val d = InputDispatcher(router, active)

        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals("western", active.active.value?.id)
        d.handleKeyEventForTest(deviceId = 8, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals("nintendo", active.active.value?.id)
    }

    @Test
    fun repeat_event_re_fires_callback_for_non_nav_canonicals() {
        // Non-nav canonical (BTN_SOUTH) still repeats via the dispatcher. Nav canonicals
        // (BTN_UP/DOWN/LEFT/RIGHT) intentionally do not -- MenuNavigationPoller drives those.
        val (d, _, _) = setup(westernTemplate())
        var back = 0
        d.onBack = { back++ }  // western template: BTN_SOUTH -> back
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 1)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 2)
        assertEquals(3, back)
    }

    @Test
    fun nav_repeat_events_are_suppressed_in_dispatcher() {
        // Nav buttons auto-repeat via MenuNavigationPoller polling PortRouter held state.
        // Android-supplied keycode repeats for nav buttons are dropped in the dispatcher so
        // the poller is the sole repeat source. Initial press still fires.
        val (d, _, _) = setup(westernTemplate())
        var up = 0
        d.onUp = { up++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 1)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 2)
        assertEquals(1, up)
    }

    @Test
    fun unknown_deviceId_returns_false() {
        val router = PortRouter()
        val d = InputDispatcher(router, ActiveMappingHolder())
        val handled = d.handleKeyEventForTest(deviceId = 999, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertFalse(handled)
    }

    @Test
    fun handleKeyEvent_returns_false_for_unbound_keycode() {
        val (d, _, _) = setup(westernTemplate())
        val handled = d.handleKeyEventForTest(deviceId = 7, keyCode = 200, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertFalse(handled)
    }

    @Test
    fun released_other_than_select_does_not_fire_callbacks() {
        val (d, _, _) = setup(westernTemplate())
        var back = 0
        var confirm = 0
        d.onBack = { back++ }
        d.onConfirm = { confirm++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(1, back)
        assertEquals(0, confirm)
    }

    @Test
    fun motion_event_axis_crossing_threshold_fires_callback() {
        val template = westernTemplate().copy(
            bindings = westernTemplate().bindings + (CanonicalButton.BTN_L2 to listOf(InputBinding.Axis(
                axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f,
            )))
        )
        val router = PortRouter()
        router.onConnect(device(7), template)
        router.markLaunchTrigger(7)
        val d = InputDispatcher(router, ActiveMappingHolder())
        var l2 = 0
        d.onL2 = { l2++ }
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(17 to 0.8f))
        assertEquals(1, l2)
    }

    @Test
    fun phantom_device_never_activates_or_dispatches() {
        val router = PortRouter()
        router.onConnect(device(7), westernTemplate())
        val d = InputDispatcher(router, ActiveMappingHolder())
        var south = 0
        d.onBack = { south++ }
        // Nothing ever fires; phantom stays pending.
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(0 to 0.0f, 1 to 0.0f))
        assertFalse(router.isActivated(7))
        org.junit.Assert.assertNull(router.portFor(7))
        assertEquals(0, south)
    }

    @Test
    fun first_press_activates_and_assigns_port() {
        val router = PortRouter()
        router.onConnect(device(7), westernTemplate())
        val d = InputDispatcher(router, ActiveMappingHolder())
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertTrue(router.isActivated(7))
        assertEquals(0, router.portFor(7))
    }

    @Test
    fun first_motion_axis_press_activates_and_assigns_port() {
        val template = westernTemplate().copy(
            bindings = westernTemplate().bindings + (CanonicalButton.BTN_L2 to listOf(InputBinding.Axis(
                axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f, digitalThreshold = 0.5f,
            )))
        )
        val router = PortRouter()
        router.onConnect(device(7), template)
        val d = InputDispatcher(router, ActiveMappingHolder())
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(17 to 0.8f))
        assertTrue(router.isActivated(7))
        assertEquals(0, router.portFor(7))
    }

    @Test
    fun first_to_press_wins_port_zero() {
        val router = PortRouter()
        router.onConnect(device(7), westernTemplate())
        router.onConnect(device(8), westernTemplate())
        val d = InputDispatcher(router, ActiveMappingHolder())
        var t = 0L
        d.clock = { ++t }
        // Device 8 presses first.
        d.handleKeyEventForTest(deviceId = 8, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        // Then device 7.
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(0, router.portFor(8))
        assertEquals(1, router.portFor(7))
    }

    @Test
    fun action_up_alone_does_not_activate() {
        val router = PortRouter()
        router.onConnect(device(7), westernTemplate())
        val d = InputDispatcher(router, ActiveMappingHolder())
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertFalse(router.isActivated(7))
    }

    @Test
    fun hat_bound_dpad_fires_initial_press_only_in_dispatcher() {
        // Template binds BTN_UP via Hat (axis 16, UP) -- no Button binding for keycode 19. The
        // dispatcher fires once on the initial axis crossing; subsequent Android keycode repeats
        // for the synthesized KEYCODE_DPAD_UP are dropped because nav auto-repeat is owned by
        // MenuNavigationPoller (held-state poll, source-agnostic).
        val template = westernTemplate().copy(
            bindings = westernTemplate().bindings + (CanonicalButton.BTN_UP to listOf(
                InputBinding.Hat(axis = 16, direction = HatDirection.UP, threshold = 0.5f)
            ))
        )
        val router = PortRouter()
        router.onConnect(device(7), template)
        router.markLaunchTrigger(7)
        val d = InputDispatcher(router, ActiveMappingHolder())
        var up = 0
        d.onUp = { up++ }

        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(16 to -1f))
        org.junit.Assert.assertEquals(1, up)

        d.handleKeyEventForTest(deviceId = 7, keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 1)
        d.handleKeyEventForTest(deviceId = 7, keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 2)
        org.junit.Assert.assertEquals(1, up)
    }

    @Test
    fun wireToRegistry_routes_every_canonical_callback_through_registry_top() {
        val registry = ScreenInputRegistry()
        var observed: String? = null
        val handler = object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onUp() { observed = "up" }
            override fun onDown() { observed = "down" }
            override fun onLeft() { observed = "left" }
            override fun onRight() { observed = "right" }
            override fun onConfirm() { observed = "confirm" }
            override fun onBack() { observed = "back" }
            override fun onStart() { observed = "start" }
            override fun onSelect() { observed = "select" }
            override fun onSelectUp() { observed = "selectUp" }
            override fun onNorth() { observed = "north" }
            override fun onWest() { observed = "west" }
            override fun onL1() { observed = "l1" }
            override fun onR1() { observed = "r1" }
            override fun onL2() { observed = "l2" }
            override fun onR2() { observed = "r2" }
        }
        registry.push(handler)
        val dispatcher = InputDispatcher(PortRouter(), ActiveMappingHolder(), registry)
        dispatcher.wireToRegistry(dialogHandler = null)

        dispatcher.onUp(); assertEquals("up", observed)
        dispatcher.onDown(); assertEquals("down", observed)
        dispatcher.onLeft(); assertEquals("left", observed)
        dispatcher.onRight(); assertEquals("right", observed)
        dispatcher.onConfirm(); assertEquals("confirm", observed)
        dispatcher.onBack(); assertEquals("back", observed)
        dispatcher.onStart(); assertEquals("start", observed)
        dispatcher.onSelect(); assertEquals("select", observed)
        dispatcher.onSelectUp(); assertEquals("selectUp", observed)
        dispatcher.onNorth(); assertEquals("north", observed)
        dispatcher.onWest(); assertEquals("west", observed)
        dispatcher.onL1(); assertEquals("l1", observed)
        dispatcher.onR1(); assertEquals("r1", observed)
        dispatcher.onL2(); assertEquals("l2", observed)
        dispatcher.onR2(); assertEquals("r2", observed)
    }

    @Test
    fun wireToRegistry_dialog_handler_takes_precedence_over_screen() {
        val registry = ScreenInputRegistry()
        var dialogHit = false
        var screenHit = false
        val screenHandler = object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onUp() { screenHit = true }
        }
        registry.push(screenHandler)
        val dialog = object : dev.cannoli.scorza.input.DialogPrecedence {
            override fun onUp(): Boolean { dialogHit = true; return true }
        }
        val dispatcher = InputDispatcher(PortRouter(), ActiveMappingHolder(), registry)
        dispatcher.wireToRegistry(dialogHandler = dialog)

        dispatcher.onUp()
        assertTrue(dialogHit)
        assertFalse(screenHit)
    }

    @Test
    fun wireToRegistry_dialog_handler_falls_through_when_it_does_not_consume() {
        val registry = ScreenInputRegistry()
        var dialogHit = false
        var screenHit = false
        val screenHandler = object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onUp() { screenHit = true }
        }
        registry.push(screenHandler)
        val dialog = object : dev.cannoli.scorza.input.DialogPrecedence {
            override fun onUp(): Boolean { dialogHit = true; return false }
        }
        val dispatcher = InputDispatcher(PortRouter(), ActiveMappingHolder(), registry)
        dispatcher.wireToRegistry(dialogHandler = dialog)

        dispatcher.onUp()
        assertTrue(dialogHit)
        assertTrue(screenHit)
    }

    @Test
    fun dpad_up_via_hat_axis_fires_onUp() {
        val template = DeviceMapping(
            id = "hat-pad",
            displayName = "Hat Pad",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Hat(
                        axis = android.view.MotionEvent.AXIS_HAT_Y,
                        direction = HatDirection.UP,
                    )
                ),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
            glyphStyle = GlyphStyle.PLUMBER,
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        val (d, _, _) = setup(template)
        var up = 0
        d.onUp = { up++ }
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(android.view.MotionEvent.AXIS_HAT_Y to -1f))
        assertEquals(1, up)
    }

    @Test
    fun dpad_keycode_auto_repeat_is_dropped_when_evaluator_already_holds_canonical() {
        // GameSir Pocket Taco scenario: device emits AXIS_HAT_Y motion (canonical held) and then
        // Android also synthesizes KEYCODE_DPAD_UP with repeatCount > 0. The repeat must not produce
        // a second onUp(); MenuNavigationPoller is responsible for held-state nav repeat.
        val template = DeviceMapping(
            id = "hybrid-pad",
            displayName = "Hybrid Pad",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Button(19),
                    InputBinding.Hat(
                        axis = android.view.MotionEvent.AXIS_HAT_Y,
                        direction = HatDirection.UP,
                    ),
                ),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
            glyphStyle = GlyphStyle.PLUMBER,
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        val (d, _, _) = setup(template)
        var up = 0
        d.onUp = { up++ }
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(android.view.MotionEvent.AXIS_HAT_Y to -1f))
        assertEquals(1, up)
        // Synthesized keycode auto-repeat for the same canonical must not double-fire.
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 1)
        assertEquals(1, up)
    }

    private fun templateWithNorth() = DeviceMapping(
        id = "north",
        displayName = "North",
        match = DeviceMatchRule(),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
            CanonicalButton.BTN_NORTH to listOf(InputBinding.Button(99)),
            CanonicalButton.BTN_SELECT to listOf(InputBinding.Button(109)),
        ),
        menuConfirm = CanonicalButton.BTN_EAST,
        menuBack = CanonicalButton.BTN_SOUTH,
        glyphStyle = GlyphStyle.REDMOND,
        source = MappingSource.RETROARCH_AUTOCONFIG,
    )

    @Test
    fun confirm_button_release_fires_onConfirmUp() {
        val (d, _, _) = setup(westernTemplate())
        var up = 0
        d.onConfirmUp = { up++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(1, up)
    }

    @Test
    fun back_button_release_does_not_fire_onConfirmUp() {
        val (d, _, _) = setup(westernTemplate())
        var up = 0
        d.onConfirmUp = { up++ }
        // keycode 96 is BTN_SOUTH = menuBack for westernTemplate
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 96, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(0, up)
    }

    @Test
    fun north_button_release_fires_onNorthUp() {
        val (d, _, _) = setup(templateWithNorth())
        var up = 0
        d.onNorthUp = { up++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 99, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 99, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(1, up)
    }

    @Test
    fun dpad_keycode_initial_press_after_hat_assertion_does_not_double_fire() {
        // Hybrid pad (HAT + KEYCODE_DPAD): HAT fires first, then a non-repeat synthesized
        // KEYCODE_DPAD_UP arrives. The evaluator's per-source assert tracking dedupes the
        // initial press (BTN_UP already asserted from the HAT path) so onUp fires only once.
        // This complements the auto-repeat dedup case above and pins the contract for the
        // first press transition.
        val template = DeviceMapping(
            id = "hybrid-pad-initial",
            displayName = "Hybrid Pad",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Button(19),
                    InputBinding.Hat(
                        axis = android.view.MotionEvent.AXIS_HAT_Y,
                        direction = HatDirection.UP,
                    ),
                ),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
            glyphStyle = GlyphStyle.PLUMBER,
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        val (d, _, _) = setup(template)
        var up = 0
        d.onUp = { up++ }
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(android.view.MotionEvent.AXIS_HAT_Y to -1f))
        assertEquals(1, up)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 19, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, up)
    }

    @Test
    fun screenResolver_overrides_registry_for_canonical_dispatch() {
        val (d, _, _) = setup(westernTemplate())
        var fired = 0
        val resolved = object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onConfirm() { fired++ }
        }
        d.wireToRegistry(screenResolver = { resolved })
        // keycode 97 = BTN_EAST = menuConfirm for westernTemplate
        d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        assertEquals(1, fired)
    }

    @Test
    fun rewiring_without_resolver_clears_a_previously_set_resolver() {
        val (d, _, _) = setup(westernTemplate())
        var fired = 0
        val resolved = object : dev.cannoli.scorza.input.ScreenInputHandler {
            override fun onConfirm() { fired++ }
        }
        d.wireToRegistry(screenResolver = { resolved })  // launcher-style wiring
        d.wireToRegistry()                               // IGM-style re-wire must reset the resolver
        d.handleKeyEventForTest(deviceId = 7, keyCode = 97, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        // resolver cleared -> dispatch goes to registry top (empty), not the launcher resolver
        assertEquals(0, fired)
    }

    @Test
    fun dpad_down_release_fires_onDownRelease_via_keycode() {
        val template = westernTemplate().copy(
            bindings = westernTemplate().bindings + (CanonicalButton.BTN_DOWN to listOf(InputBinding.Button(20)))
        )
        val (d, _, _) = setup(template)
        var down = 0
        var downRelease = 0
        d.onDown = { down++ }
        d.onDownRelease = { downRelease++ }
        d.handleKeyEventForTest(deviceId = 7, keyCode = 20, action = android.view.KeyEvent.ACTION_DOWN, repeatCount = 0)
        d.handleKeyEventForTest(deviceId = 7, keyCode = 20, action = android.view.KeyEvent.ACTION_UP, repeatCount = 0)
        assertEquals(1, down)
        assertEquals(1, downRelease)
    }

    @Test
    fun dpad_up_release_via_hat_axis_neutral_fires_onUpRelease() {
        val template = DeviceMapping(
            id = "hat-pad",
            displayName = "Hat Pad",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Hat(axis = android.view.MotionEvent.AXIS_HAT_Y, direction = HatDirection.UP)
                ),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
            glyphStyle = GlyphStyle.PLUMBER,
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
        val (d, _, _) = setup(template)
        var up = 0
        var upRelease = 0
        d.onUp = { up++ }
        d.onUpRelease = { upRelease++ }
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(android.view.MotionEvent.AXIS_HAT_Y to -1f))
        d.handleMotionEventForTest(deviceId = 7, axisValues = mapOf(android.view.MotionEvent.AXIS_HAT_Y to 0f))
        assertEquals(1, up)
        assertEquals(1, upRelease)
    }
}
