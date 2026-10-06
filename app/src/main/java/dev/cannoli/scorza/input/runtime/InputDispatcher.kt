package dev.cannoli.scorza.input.runtime

import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.igm.MenuAction
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.DeviceMapping
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class InputDispatcher @Inject constructor(
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val screenInputRegistry: ScreenInputRegistry = ScreenInputRegistry(),
) {

    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val menuShortcuts = dev.cannoli.igm.MenuShortcutGate()

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    internal var schedule: (delayMs: Long, block: () -> Unit) -> Unit = { delayMs, block ->
        mainHandler.postDelayed(block, delayMs)
    }

    fun setMenuShortcuts(shortcuts: Map<dev.cannoli.igm.ShortcutAction, Set<Int>>) {
        menuShortcuts.setShortcuts(shortcuts)
        heldBackOn.clear()
    }

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

    private var screenResolver: (() -> dev.cannoli.scorza.input.ScreenInputHandler)? = null

    private fun activeScreen(): dev.cannoli.scorza.input.ScreenInputHandler =
        screenResolver?.invoke() ?: screenInputRegistry.top

    fun handleKeyEvent(event: KeyEvent): Boolean {
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
        if (menuShortcuts.isBound) {
            menuShortcutKey(deviceId, keyCode, action, repeatCount, evaluator, mapping)?.let { return it }
        }
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
                    if (deltas.isEmpty()) {
                        if (mapping.source == MappingSource.UNIDENTIFIED) maybeActivate(deviceId)
                        return false
                    }
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

    private fun menuShortcutKey(
        deviceId: Int,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        evaluator: PortEvaluator,
        mapping: DeviceMapping,
    ): Boolean? {
        val gate = menuShortcuts
        when (action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE -> {
                val isRepeat = action == KeyEvent.ACTION_MULTIPLE || repeatCount > 0
                val result = gate.onKeyDown(keyCode, clock(), isRepeat)
                gate.takeReplays().forEach(::replayPress)
                return when (result) {
                    dev.cannoli.igm.MenuShortcutGate.Result.PASS -> null
                    dev.cannoli.igm.MenuShortcutGate.Result.CONSUME -> {
                        if (!isRepeat) {
                            evaluator.evaluateKeyDown(keyCode, isAndroidRepeat = false)
                            heldBackOn[keyCode] = deviceId
                            gate.nextDeadline()?.let { deadline ->
                                schedule((deadline - clock()).coerceAtLeast(0L)) { menuShortcutTick() }
                            }
                        }
                        true
                    }
                    dev.cannoli.igm.MenuShortcutGate.Result.MENU -> {
                        evaluator.evaluateKeyDown(keyCode, isAndroidRepeat = false)
                        maybeActivate(deviceId)
                        activeMappingHolder.set(mapping)
                        onMenu()
                        true
                    }
                    dev.cannoli.igm.MenuShortcutGate.Result.REPLAY_PRESS -> null
                }
            }
            KeyEvent.ACTION_UP -> {
                val result = gate.onKeyUp(keyCode)
                gate.takeReplays().forEach(::replayPress)
                return when (result) {
                    dev.cannoli.igm.MenuShortcutGate.Result.PASS -> null
                    dev.cannoli.igm.MenuShortcutGate.Result.CONSUME -> {
                        heldBackOn.remove(keyCode)
                        evaluator.evaluateKeyUp(keyCode)
                        true
                    }
                    dev.cannoli.igm.MenuShortcutGate.Result.REPLAY_PRESS -> {
                        replayPress(keyCode)
                        null
                    }
                    dev.cannoli.igm.MenuShortcutGate.Result.MENU -> null
                }
            }
            else -> return null
        }
    }

    private val heldBackOn = mutableMapOf<Int, Int>()

    private fun menuShortcutTick() {
        val result = menuShortcuts.onTick(clock())
        menuShortcuts.takeReplays().forEach(::replayPress)
        if (result == dev.cannoli.igm.MenuShortcutGate.Result.MENU) onMenu()
    }

    private fun replayPress(keyCode: Int) {
        val deviceId = heldBackOn.remove(keyCode) ?: return
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return
        val mapping = portRouter.mappingFor(deviceId) ?: return
        maybeActivate(deviceId)
        activeMappingHolder.set(mapping)
        for (canonical in evaluator.canonicalsHeldByKeyCode(keyCode)) dispatchPressed(canonical, mapping)
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
        val action = dev.cannoli.igm.menuActionFor(canonical, mapping.menuConfirm, mapping.menuBack)
            ?: return false
        when (action) {
            MenuAction.UP -> onUp()
            MenuAction.DOWN -> onDown()
            MenuAction.LEFT -> onLeft()
            MenuAction.RIGHT -> onRight()
            MenuAction.CONFIRM -> onConfirm()
            MenuAction.BACK -> onBack()
            MenuAction.WEST -> onWest()
            MenuAction.NORTH -> onNorth()
            MenuAction.L1 -> onL1()
            MenuAction.R1 -> onR1()
            MenuAction.L2 -> onL2()
            MenuAction.R2 -> onR2()
            MenuAction.L3 -> onL3()
            MenuAction.R3 -> onR3()
            MenuAction.START -> onStart()
            MenuAction.SELECT -> onSelect()
            MenuAction.MENU -> onMenu()
        }
        return true
    }

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
