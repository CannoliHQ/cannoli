package dev.cannoli.scorza.input.runtime

import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class InputDispatcher @Inject constructor(
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val screenInputRegistry: ScreenInputRegistry = ScreenInputRegistry(),
) {

    /** Overridable for tests; production calls System.currentTimeMillis. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    var onUp: () -> Unit = {}
    var onDown: () -> Unit = {}
    var onLeft: () -> Unit = {}
    var onRight: () -> Unit = {}
    var onUpRelease: () -> Unit = {}
    var onDownRelease: () -> Unit = {}
    var onLeftRelease: () -> Unit = {}
    var onRightRelease: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onSelectUp: () -> Unit = {}
    var onConfirmUp: () -> Unit = {}
    var onNorthUp: () -> Unit = {}
    var onStart: () -> Unit = {}
    var onL1: () -> Unit = {}
    var onR1: () -> Unit = {}
    var onL2: () -> Unit = {}
    var onR2: () -> Unit = {}
    var onL3: () -> Unit = {}
    var onR3: () -> Unit = {}
    var onWest: () -> Unit = {}
    var onNorth: () -> Unit = {}
    var onMenu: () -> Unit = {}

    // When set, resolves the active screen handler synchronously from nav state instead of the
    // recompose-lagging registry. Set per wiring via wireToRegistry: the launcher passes one, the
    // IGM passes null so it falls back to registry top. Reset on every wireToRegistry call so the
    // last activity to wire wins (the IGM clears the launcher's resolver when it takes over).
    private var screenResolver: (() -> dev.cannoli.scorza.input.ScreenInputHandler)? = null

    private fun activeScreen(): dev.cannoli.scorza.input.ScreenInputHandler =
        screenResolver?.invoke() ?: screenInputRegistry.top

    fun handleKeyEvent(event: KeyEvent): Boolean {
        // Raw event hook: screens that need keycode/deviceId/repeatCount (binding capture,
        // shortcut chord capture) override onRawKeyDown/onRawKeyUp and can short-circuit before
        // canonical dispatch.
        val handler = activeScreen()
        if (handler !== dev.cannoli.scorza.input.screen.EmptyScreenInputHandler) {
            val consumed = when (event.action) {
                KeyEvent.ACTION_DOWN -> handler.onRawKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> handler.onRawKeyUp(event.keyCode, event)
                else -> false
            }
            if (consumed) return true
        }
        return handleKeyEventForTest(
            deviceId = event.deviceId,
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
        )
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val axisValues = device.motionRanges.associate { it.axis to event.getAxisValue(it.axis) }
        return handleMotionEventForTest(event.deviceId, axisValues)
    }

    internal fun handleKeyEventForTest(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val mapping = portRouter.mappingFor(deviceId) ?: return false
        return when (action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE -> {
                val isRepeat = action == KeyEvent.ACTION_MULTIPLE || repeatCount > 0
                if (isRepeat) {
                    val direct = evaluator.canonicalsHeldByKeyCode(keyCode)
                    val fallback = if (direct.isEmpty()) dpadFallbackForRepeat(evaluator, keyCode) else emptyList()
                    val canonicals = (if (direct.isNotEmpty()) direct else fallback)
                        .filter { it !in NAV_CANONICALS }
                    // Nav auto-repeat is driven by MenuNavigationPoller (held-state poll on
                    // PortRouter). Android keycode auto-repeats for nav buttons are dropped here
                    // so pads that emit both hat motion and synthesized KEYCODE_DPAD_* (GameSir
                    // Pocket Taco) do not double-navigate.
                    if (canonicals.isEmpty()) return false
                    maybeActivate(deviceId)
                    activeMappingHolder.set(mapping)
                    var fired = false
                    for (canonical in canonicals) {
                        if (dispatchPressed(canonical, mapping)) fired = true
                    }
                    fired
                } else {
                    val deltas = evaluator.evaluateKeyDown(keyCode, isAndroidRepeat = false)
                    if (deltas.isNotEmpty()) {
                        dev.cannoli.scorza.util.InputLog.write(
                            "key id=$deviceId code=$keyCode -> " + deltas.joinToString(",") {
                                if (it is CanonicalEvent.Pressed) it.button.name else "release"
                            }
                        )
                    } else {
                        dev.cannoli.scorza.util.InputLog.write("key id=$deviceId code=$keyCode -> unbound")
                    }
                    if (deltas.isEmpty()) return false
                    maybeActivate(deviceId)
                    activeMappingHolder.set(mapping)
                    var fired = false
                    for (delta in deltas) {
                        if (delta is CanonicalEvent.Pressed && dispatchPressed(delta.button, mapping)) fired = true
                    }
                    fired
                }
            }
            KeyEvent.ACTION_UP -> {
                val deltas = evaluator.evaluateKeyUp(keyCode)
                if (deltas.isEmpty()) return false
                var fired = false
                for (delta in deltas) {
                    if (delta !is CanonicalEvent.Released) continue
                    when (delta.button) {
                        CanonicalButton.BTN_SELECT -> { onSelectUp(); fired = true }
                        CanonicalButton.BTN_NORTH -> { onNorthUp(); fired = true }
                        CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST -> {
                            if (mapping.menuConfirm == delta.button) { onConfirmUp(); fired = true }
                        }
                        CanonicalButton.BTN_UP -> { onUpRelease(); fired = true }
                        CanonicalButton.BTN_DOWN -> { onDownRelease(); fired = true }
                        CanonicalButton.BTN_LEFT -> { onLeftRelease(); fired = true }
                        CanonicalButton.BTN_RIGHT -> { onRightRelease(); fired = true }
                        else -> {}
                    }
                }
                fired
            }
            else -> false
        }
    }

    internal fun handleMotionEventForTest(deviceId: Int, axisValues: Map<Int, Float>): Boolean {
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val mapping = portRouter.mappingFor(deviceId) ?: return false
        val deltas = evaluator.evaluateAxis(axisValues)
        if (deltas.isEmpty()) return false
        var fired = false
        for (delta in deltas) {
            if (delta is CanonicalEvent.Pressed) {
                maybeActivate(deviceId)
                activeMappingHolder.set(mapping)
                if (dispatchPressed(delta.button, mapping)) fired = true
            } else if (delta is CanonicalEvent.Released) {
                when (delta.button) {
                    CanonicalButton.BTN_SELECT -> { onSelectUp(); fired = true }
                    CanonicalButton.BTN_UP -> { onUpRelease(); fired = true }
                    CanonicalButton.BTN_DOWN -> { onDownRelease(); fired = true }
                    CanonicalButton.BTN_LEFT -> { onLeftRelease(); fired = true }
                    CanonicalButton.BTN_RIGHT -> { onRightRelease(); fired = true }
                    else -> {}
                }
            }
        }
        return fired
    }

    private fun maybeActivate(deviceId: Int) {
        if (!portRouter.isActivated(deviceId)) {
            portRouter.activate(deviceId, clock())
        }
    }

    private fun dpadFallbackForRepeat(evaluator: PortEvaluator, keyCode: Int): List<CanonicalButton> {
        val canonical = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> CanonicalButton.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CanonicalButton.BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> CanonicalButton.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CanonicalButton.BTN_RIGHT
            else -> return emptyList()
        }
        return if (evaluator.currentlyPressed().contains(canonical)) listOf(canonical) else emptyList()
    }

    private fun dispatchPressed(canonical: CanonicalButton, mapping: DeviceMapping): Boolean {
        when (canonical) {
            CanonicalButton.BTN_UP -> onUp()
            CanonicalButton.BTN_DOWN -> onDown()
            CanonicalButton.BTN_LEFT -> onLeft()
            CanonicalButton.BTN_RIGHT -> onRight()
            CanonicalButton.BTN_EAST -> when {
                mapping.menuConfirm == CanonicalButton.BTN_EAST -> onConfirm()
                mapping.menuBack == CanonicalButton.BTN_EAST -> onBack()
                else -> return false
            }
            CanonicalButton.BTN_SOUTH -> when {
                mapping.menuConfirm == CanonicalButton.BTN_SOUTH -> onConfirm()
                mapping.menuBack == CanonicalButton.BTN_SOUTH -> onBack()
                else -> return false
            }
            CanonicalButton.BTN_WEST -> onWest()
            CanonicalButton.BTN_NORTH -> onNorth()
            CanonicalButton.BTN_L -> onL1()
            CanonicalButton.BTN_R -> onR1()
            CanonicalButton.BTN_L2 -> onL2()
            CanonicalButton.BTN_R2 -> onR2()
            CanonicalButton.BTN_L3 -> onL3()
            CanonicalButton.BTN_R3 -> onR3()
            CanonicalButton.BTN_START -> onStart()
            CanonicalButton.BTN_SELECT -> onSelect()
            CanonicalButton.BTN_MENU -> onMenu()
        }
        return true
    }

    /**
     * Wires the dispatcher's canonical-event callbacks to its [ScreenInputRegistry]. Every
     * subscriber Activity (MainActivity for the launcher, LibretroActivity for the IGM) calls
     * this once during initialization so the two contexts share one wiring path and cannot drift.
     *
     * [dialogHandler] is consulted before the registry's top handler. Pass null on the IGM
     * (no dialog layer) and the launcher's DialogInputHandler on the launcher. The handler
     * returns true to consume the event; on false, dispatch falls through to the screen handler.
     *
     * The launcher passes [onSelectUpOverride] because it owns select-hold nav-flag state that
     * the generic helper cannot know about; on the IGM and in tests the override stays null and
     * the helper's default body (cancel-hold then dialog-or-screen) is used.
     */
    fun wireToRegistry(
        dialogHandler: dev.cannoli.scorza.input.DialogPrecedence? = null,
        onSelectUpOverride: (() -> Unit)? = null,
        screenResolver: (() -> dev.cannoli.scorza.input.ScreenInputHandler)? = null,
    ) {
        this.screenResolver = screenResolver
        fun screen(): dev.cannoli.scorza.input.ScreenInputHandler = activeScreen()
        onUp = { if (dialogHandler?.onUp() != true) screen().onUp() }
        onDown = { if (dialogHandler?.onDown() != true) screen().onDown() }
        onLeft = { if (dialogHandler?.onLeft() != true) screen().onLeft() }
        onRight = { if (dialogHandler?.onRight() != true) screen().onRight() }
        onUpRelease = { screen().onUpRelease() }
        onDownRelease = { screen().onDownRelease() }
        onLeftRelease = { screen().onLeftRelease() }
        onRightRelease = { screen().onRightRelease() }
        onConfirm = { if (dialogHandler?.onConfirm() != true) screen().onConfirm() }
        onBack = { if (dialogHandler?.onBack() != true) screen().onBack() }
        onStart = { if (dialogHandler?.onStart() != true) screen().onStart() }
        onSelect = { if (dialogHandler?.onSelect() != true) screen().onSelect() }
        onSelectUp = onSelectUpOverride ?: {
            dialogHandler?.cancelSelectHold()
            if (dialogHandler?.onSelectUp() != true) screen().onSelectUp()
        }
        onConfirmUp = { if (dialogHandler?.onConfirmUp() != true) screen().onConfirmUp() }
        onNorthUp = { if (dialogHandler?.onNorthUp() != true) screen().onNorthUp() }
        onNorth = { if (dialogHandler?.onNorth() != true) screen().onNorth() }
        onWest = { if (dialogHandler?.onWest() != true) screen().onWest() }
        onL1 = { if (dialogHandler?.onL1() != true) screen().onL1() }
        onR1 = { if (dialogHandler?.onR1() != true) screen().onR1() }
        onL2 = { if (dialogHandler?.onL2() != true) screen().onL2() }
        onR2 = { if (dialogHandler?.onR2() != true) screen().onR2() }
        onL3 = { if (dialogHandler?.onL3() != true) screen().onL3() }
        onR3 = { if (dialogHandler?.onR3() != true) screen().onR3() }
        onMenu = { if (dialogHandler?.onMenu() != true) screen().onMenu() }
    }

    companion object {
        private val NAV_CANONICALS = setOf(
            CanonicalButton.BTN_UP,
            CanonicalButton.BTN_DOWN,
            CanonicalButton.BTN_LEFT,
            CanonicalButton.BTN_RIGHT,
        )
    }
}
