package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.PortRouter
import dev.cannoli.scorza.ui.viewmodel.DeviceInfo
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel

class InputTesterController(
    private val viewModel: InputTesterViewModel,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val unknownDeviceName: String,
    private val keyboardDeviceName: String,
) {
    private val pressedKeycodes = mutableMapOf<Int, CanonicalButton?>()
    private var selectHeld = false
    private var startHeld = false
    private val axisTriggerL2Held = mutableSetOf<Int>()
    private val axisTriggerR2Held = mutableSetOf<Int>()
    private val exitHandler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { viewModel.requestExit() }

    fun enter() {
        portRouter.resetAllEvaluators()
        viewModel.reset()
        pressedKeycodes.clear()
        selectHeld = false
        startHeld = false
        exitHandler.removeCallbacks(exitRunnable)
        refreshPorts()
    }

    fun exit() {
        portRouter.resetAllEvaluators()
    }

    fun dispatchKey(event: KeyEvent, down: Boolean): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) return true
        val device = event.device
        val deviceId = event.deviceId
        val port = if (device != null) portRouter.portFor(deviceId) ?: 0 else 0
        val name = portRouter.mappingForPort(port)?.displayName?.takeIf { it.isNotEmpty() }
            ?: device?.name?.takeIf { it.isNotEmpty() }
            ?: keyboardDeviceName
        val keyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        val mappingNav = mappingNavButtonFor(portRouter.mappingForPort(port), event.keyCode)
        val navButton = mappingNav ?: AndroidGamepadKeyNames.DEFAULT_KEY_MAP[event.keyCode]
        val unbound = mappingNav == null && navButton != null

        if (down) {
            val isRepeat = event.repeatCount > 0
            if (navButton == CanonicalButton.BTN_SELECT && !selectHeld) {
                selectHeld = true
                updateExitCountdown()
            }
            if (navButton == CanonicalButton.BTN_START && !startHeld) {
                startHeld = true
                updateExitCountdown()
            }
            if (!isRepeat && selectHeld && navButton == CanonicalButton.BTN_NORTH) {
                viewModel.toggleAxisDump()
            }
            pressedKeycodes[event.keyCode] = navButton
            viewModel.onKeyDown(port, event.keyCode, keyName, deviceId, name, navButton, unbound = unbound)
            if (!isRepeat) {
                viewModel.setActivePort(port)
                portRouter.mappingForPort(port)?.let { activeMappingHolder.set(it) }
            }
        } else {
            if (navButton == CanonicalButton.BTN_SELECT && selectHeld) {
                selectHeld = false
                updateExitCountdown()
            }
            if (navButton == CanonicalButton.BTN_START && startHeld) {
                startHeld = false
                updateExitCountdown()
            }
            val resolved = pressedKeycodes.remove(event.keyCode)
            viewModel.onKeyUp(port, event.keyCode, keyName, deviceId, name, resolved, unbound = unbound)
        }
        refreshPorts()
        return true
    }

    fun dispatchMotion(event: MotionEvent): Boolean {
        val deviceId = event.deviceId
        val port = portRouter.portFor(deviceId) ?: 0
        val mapping = portRouter.mappingForPort(port)
        val name = mapping?.displayName?.takeIf { it.isNotEmpty() }
            ?: event.device?.name?.takeIf { it.isNotEmpty() }
            ?: unknownDeviceName
        val leftX = mostActive(mappingStickValue(mapping, CanonicalButton.BTN_LSTICK_X, event), event.getAxisValue(MotionEvent.AXIS_X))
        val leftY = mostActive(mappingStickValue(mapping, CanonicalButton.BTN_LSTICK_Y, event), event.getAxisValue(MotionEvent.AXIS_Y))
        val rightX = mostActive(mappingStickValue(mapping, CanonicalButton.BTN_RSTICK_X, event), event.getAxisValue(MotionEvent.AXIS_Z))
        val rightY = mostActive(mappingStickValue(mapping, CanonicalButton.BTN_RSTICK_Y, event), event.getAxisValue(MotionEvent.AXIS_RZ))
        val leftTrigger = maxOf(
            mappingTriggerDisplayValue(mapping, CanonicalButton.BTN_L2, event) ?: 0f,
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f),
            event.getAxisValue(MotionEvent.AXIS_BRAKE).coerceIn(0f, 1f),
        )
        val rightTrigger = maxOf(
            mappingTriggerDisplayValue(mapping, CanonicalButton.BTN_R2, event) ?: 0f,
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f),
            event.getAxisValue(MotionEvent.AXIS_GAS).coerceIn(0f, 1f),
        )
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        viewModel.onMotion(
            port = port, deviceId = deviceId, deviceName = name,
            leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
            hatButtons = mappingHatButtons(mapping) { event.getAxisValue(it) } ?: rawHatButtons(hatX, hatY),
        )

        val activatesPort = leftTrigger > 0.1f || rightTrigger > 0.1f ||
            kotlin.math.abs(hatX) > 0.5f || kotlin.math.abs(hatY) > 0.5f
        if (activatesPort) {
            viewModel.setActivePort(port)
            mapping?.let { activeMappingHolder.set(it) }
        }

        val dumpAxes = event.device?.motionRanges?.map { it.axis }?.distinct() ?: emptyList()
        viewModel.recordAxisValues(port, dumpAxes.associateWith { event.getAxisValue(it) })

        syncAxisTrigger(
            port, deviceId, name, KeyEvent.KEYCODE_BUTTON_L2, leftTrigger, axisTriggerL2Held,
            CanonicalButton.BTN_L2,
            unbound = triggerUnbound(mapping, CanonicalButton.BTN_L2) { event.getAxisValue(it) },
        )
        syncAxisTrigger(
            port, deviceId, name, KeyEvent.KEYCODE_BUTTON_R2, rightTrigger, axisTriggerR2Held,
            CanonicalButton.BTN_R2,
            unbound = triggerUnbound(mapping, CanonicalButton.BTN_R2) { event.getAxisValue(it) },
        )

        return true
    }

    private fun updateExitCountdown() {
        if (selectHeld && startHeld) {
            exitHandler.removeCallbacks(exitRunnable)
            exitHandler.postDelayed(exitRunnable, 1250L)
        } else {
            exitHandler.removeCallbacks(exitRunnable)
        }
    }

    private fun mostActive(mapping: Float?, fallback: Float): Float {
        if (mapping == null) return fallback
        return if (kotlin.math.abs(mapping) >= kotlin.math.abs(fallback)) mapping else fallback
    }

    private fun syncAxisTrigger(
        port: Int,
        deviceId: Int,
        deviceName: String,
        syntheticKeyCode: Int,
        value: Float,
        held: MutableSet<Int>,
        canonical: CanonicalButton,
        unbound: Boolean,
    ) {
        val keyName = KeyEvent.keyCodeToString(syntheticKeyCode).removePrefix("KEYCODE_")
        val wasHeld = deviceId in held
        if (value > 0.5f && !wasHeld) {
            held.add(deviceId)
            viewModel.onKeyDown(port, syntheticKeyCode, keyName, deviceId, deviceName, canonical, unbound = unbound)
        } else if (value < 0.3f && wasHeld) {
            held.remove(deviceId)
            viewModel.onKeyUp(port, syntheticKeyCode, keyName, deviceId, deviceName, canonical, unbound = unbound)
        }
    }

    private fun mappingNavButtonFor(mapping: DeviceMapping?, keyCode: Int): CanonicalButton? =
        mapping?.bindings?.entries?.firstOrNull { (_, bindings) ->
            bindings.any { it is InputBinding.Button && it.keyCode == keyCode }
        }?.key

    private fun mappingTriggerDisplayValue(
        mapping: DeviceMapping?,
        canonical: CanonicalButton,
        event: MotionEvent,
    ): Float? {
        val axisBinding = boundTriggerAxis(mapping, canonical) ?: return null
        return event.getAxisValue(axisBinding.axis).coerceIn(0f, 1f)
    }

    private fun mappingStickValue(
        mapping: DeviceMapping?,
        stickCanonical: CanonicalButton,
        event: MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.get(stickCanonical)
            ?.firstNotNullOfOrNull { it as? InputBinding.Axis }
            ?: return null
        val raw = event.getAxisValue(axisBinding.axis)
        val span = axisBinding.activeMax - axisBinding.restingValue
        if (span == 0f) return 0f
        val ratio = (raw - axisBinding.restingValue) / span
        val signed = (ratio * 2f - 1f).coerceIn(-1f, 1f)
        return if (axisBinding.invert) -signed else signed
    }

    private fun refreshPorts() {
        val ports = portRouter.snapshotEntries()
            .filter { it.port != null }
            .sortedBy { it.port }
            .map { snap ->
                DeviceInfo(
                    port = snap.port ?: 0,
                    deviceId = snap.androidDeviceId,
                    name = snap.mapping.displayName.ifEmpty { snap.device.name },
                )
            }
        viewModel.setConnectedPorts(ports)
    }

}

internal val HAT_CANONICALS = listOf(
    CanonicalButton.BTN_UP,
    CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT,
    CanonicalButton.BTN_RIGHT,
)

/**
 * The d-pad reads through the mapping like the sticks and triggers do, so the tester reports the
 * binding rather than the hardware. Reading the hat sign directly agrees with the physical pad no
 * matter which direction the mapping names it, which hides a swapped binding.
 *
 * Returns null when the mapping carries no hat bindings at all, so the caller can fall back to the
 * raw hat and still show something for an unmapped pad.
 */
internal fun mappingHatButtons(mapping: DeviceMapping?, axisValue: (Int) -> Float): Set<CanonicalButton>? {
    var bound = false
    val pressed = buildSet {
        for (canonical in HAT_CANONICALS) {
            val hats = mapping?.bindings?.get(canonical)?.filterIsInstance<InputBinding.Hat>().orEmpty()
            if (hats.isEmpty()) continue
            bound = true
            if (hats.any { it.isPressed(axisValue(it.axis)) }) add(canonical)
        }
    }
    return pressed.takeIf { bound }
}

/** The axis this mapping reads [canonical] from, if it names one at all. */
internal fun boundTriggerAxis(mapping: DeviceMapping?, canonical: CanonicalButton): InputBinding.Axis? =
    mapping?.bindings?.get(canonical)
        ?.firstNotNullOfOrNull { it as? InputBinding.Axis }
        ?.takeIf { it.analogRole == AnalogRole.DIGITAL_BUTTON }

// Past this a trigger is pulled rather than resting, matching where syncAxisTrigger reports a press.
private const val TRIGGER_ACTIVE = 0.5f

/**
 * Whether the trigger that just moved reached the mapping.
 *
 * The tester reads a trigger off the raw axes as well as the bound one, so its bar fills whether or
 * not the mapping names what the pad reports, which is exactly the case worth flagging: a pad on
 * AXIS_BRAKE whose mapping names AXIS_LTRIGGER looks healthy here and does nothing in a game. So the
 * question is not whether a binding exists but whether the axis that moved is the bound one. A pad
 * reporting one trigger on both axes stays quiet, because the bound axis moves too.
 */
internal fun triggerUnbound(
    mapping: DeviceMapping?,
    canonical: CanonicalButton,
    axisValue: (Int) -> Float,
): Boolean {
    if (mapping?.bindings?.get(canonical).isNullOrEmpty()) return true
    val bound = boundTriggerAxis(mapping, canonical)
    if (bound != null && axisValue(bound.axis).coerceIn(0f, 1f) > TRIGGER_ACTIVE) return false
    return TriggerAxes.byButton[canonical].orEmpty().any {
        it != bound?.axis && axisValue(it).coerceIn(0f, 1f) > TRIGGER_ACTIVE
    }
}

internal fun rawHatButtons(hatX: Float, hatY: Float): Set<CanonicalButton> = buildSet {
    if (hatX < -0.5f) add(CanonicalButton.BTN_LEFT)
    if (hatX > 0.5f) add(CanonicalButton.BTN_RIGHT)
    if (hatY < -0.5f) add(CanonicalButton.BTN_UP)
    if (hatY > 0.5f) add(CanonicalButton.BTN_DOWN)
}
