package dev.cannoli.scorza.input.v2.runtime

import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceTemplate
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class InputDispatcher @Inject constructor(
    private val portRouter: PortRouter,
    private val activeTemplateHolder: ActiveTemplateHolder,
) {

    var onUp: () -> Unit = {}
    var onDown: () -> Unit = {}
    var onLeft: () -> Unit = {}
    var onRight: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onSelectUp: () -> Unit = {}
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

    fun handleKeyEvent(event: KeyEvent): Boolean = handleKeyEventForTest(
        deviceId = event.deviceId,
        keyCode = event.keyCode,
        action = event.action,
        repeatCount = event.repeatCount,
    )

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val axisValues = device.motionRanges.associate { it.axis to event.getAxisValue(it.axis) }
        return handleMotionEventForTest(event.deviceId, axisValues)
    }

    internal fun handleKeyEventForTest(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val template = portRouter.templateFor(deviceId) ?: return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) {
                    val direct = evaluator.canonicalsHeldByKeyCode(keyCode)
                    val fallback = if (direct.isEmpty()) dpadFallbackForRepeat(evaluator, keyCode) else emptyList()
                    val canonicals = if (direct.isNotEmpty()) direct else fallback
                    if (canonicals.isEmpty()) return false
                    activeTemplateHolder.set(template)
                    var fired = false
                    for (canonical in canonicals) {
                        if (dispatchPressed(canonical, template)) fired = true
                    }
                    fired
                } else {
                    val deltas = evaluator.evaluateKeyDown(keyCode, isAndroidRepeat = false)
                    if (deltas.isEmpty()) return false
                    activeTemplateHolder.set(template)
                    var fired = false
                    for (delta in deltas) {
                        if (delta is CanonicalEvent.Pressed && dispatchPressed(delta.button, template)) fired = true
                    }
                    fired
                }
            }
            KeyEvent.ACTION_UP -> {
                val deltas = evaluator.evaluateKeyUp(keyCode)
                if (deltas.isEmpty()) return false
                var fired = false
                for (delta in deltas) {
                    if (delta is CanonicalEvent.Released && delta.button == CanonicalButton.BTN_SELECT) {
                        onSelectUp()
                        fired = true
                    }
                }
                fired
            }
            else -> false
        }
    }

    internal fun handleMotionEventForTest(deviceId: Int, axisValues: Map<Int, Float>): Boolean {
        val evaluator = portRouter.evaluatorFor(deviceId) ?: return false
        val template = portRouter.templateFor(deviceId) ?: return false
        val deltas = evaluator.evaluateAxis(axisValues)
        if (deltas.isEmpty()) return false
        var fired = false
        for (delta in deltas) {
            if (delta is CanonicalEvent.Pressed) {
                activeTemplateHolder.set(template)
                if (dispatchPressed(delta.button, template)) fired = true
            } else if (delta is CanonicalEvent.Released && delta.button == CanonicalButton.BTN_SELECT) {
                onSelectUp()
                fired = true
            }
        }
        return fired
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

    private fun dispatchPressed(canonical: CanonicalButton, template: DeviceTemplate): Boolean {
        when (canonical) {
            CanonicalButton.BTN_UP -> onUp()
            CanonicalButton.BTN_DOWN -> onDown()
            CanonicalButton.BTN_LEFT -> onLeft()
            CanonicalButton.BTN_RIGHT -> onRight()
            CanonicalButton.BTN_EAST -> when {
                template.menuConfirm == CanonicalButton.BTN_EAST -> onConfirm()
                template.menuBack == CanonicalButton.BTN_EAST -> onBack()
                else -> return false
            }
            CanonicalButton.BTN_SOUTH -> when {
                template.menuConfirm == CanonicalButton.BTN_SOUTH -> onConfirm()
                template.menuBack == CanonicalButton.BTN_SOUTH -> onBack()
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
}
