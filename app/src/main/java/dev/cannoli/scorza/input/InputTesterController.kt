package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.runtime.PortRouter
import dev.cannoli.scorza.ui.viewmodel.DeviceInfo
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel

class InputTesterController(
    private val viewModel: InputTesterViewModel,
    private val controllerManager: ControllerManager,
    private val profileManager: ProfileManager,
    private val portRouter: PortRouter,
    private val unknownDeviceName: String,
    private val keyboardDeviceName: String,
) {
    private var profileMap: Map<Int, String> = emptyMap()
    private val pressedKeycodes = mutableMapOf<Int, String?>()
    private var selectHeld = false
    private var startHeld = false
    private var hatChordState: Int = 0
    private val exitHandler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { viewModel.requestExit() }

    fun enter() {
        viewModel.reset()
        initProfiles()
        refreshPorts()
    }

    fun dispatchKey(event: KeyEvent, down: Boolean): Boolean {
        val device = event.device
        val deviceId = event.deviceId
        val port = if (device != null) controllerManager.getPortForDeviceId(deviceId) ?: 0 else 0
        val name = device?.name ?: keyboardDeviceName
        val keyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        val navButton = templateNavButtonFor(portRouter.templateForPort(port), event.keyCode)
            ?: AndroidGamepadKeyNames.DEFAULT_KEY_MAP[event.keyCode]

        if (down) {
            val isRepeat = event.repeatCount > 0
            if (navButton == "btn_select" && !selectHeld) {
                selectHeld = true
                updateExitCountdown()
            }
            if (navButton == "btn_start" && !startHeld) {
                startHeld = true
                updateExitCountdown()
            }
            if (!isRepeat && selectHeld && (navButton == "btn_left" || navButton == "btn_right")) {
                releaseAllKeys(except = setOf("btn_select"))
                val newProfile = viewModel.cycleProfile(
                    forward = navButton == "btn_right",
                    keepPressed = setOf("btn_select"),
                )
                loadProfile(newProfile)
            }
            val resolved = profileMap[event.keyCode]
            pressedKeycodes[event.keyCode] = resolved
            viewModel.onKeyDown(port, event.keyCode, keyName, deviceId, name, resolved)
            if (!isRepeat) viewModel.setActivePort(port)
        } else {
            if (navButton == "btn_select" && selectHeld) {
                selectHeld = false
                updateExitCountdown()
            }
            if (navButton == "btn_start" && startHeld) {
                startHeld = false
                updateExitCountdown()
            }
            val resolved = pressedKeycodes.remove(event.keyCode)
            viewModel.onKeyUp(port, event.keyCode, keyName, deviceId, name, resolved)
        }
        refreshPorts()
        return true
    }

    fun dispatchMotion(event: MotionEvent): Boolean {
        val deviceId = event.deviceId
        val port = controllerManager.getPortForDeviceId(deviceId) ?: 0
        val name = event.device?.name ?: unknownDeviceName
        val template = portRouter.templateForPort(port)
        val leftX = templateStickValue(template, AnalogRole.LEFT_STICK_X, event) ?: event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = templateStickValue(template, AnalogRole.LEFT_STICK_Y, event) ?: event.getAxisValue(MotionEvent.AXIS_Y)
        val rightX = templateStickValue(template, AnalogRole.RIGHT_STICK_X, event) ?: event.getAxisValue(MotionEvent.AXIS_Z)
        val rightY = templateStickValue(template, AnalogRole.RIGHT_STICK_Y, event) ?: event.getAxisValue(MotionEvent.AXIS_RZ)
        val leftTrigger = templateTriggerValue(template, CanonicalButton.BTN_L2, event)
            ?: maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
            )
        val rightTrigger = templateTriggerValue(template, CanonicalButton.BTN_R2, event)
            ?: maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            )
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        viewModel.onMotion(
            port = port, deviceId = deviceId, deviceName = name,
            leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
            hatX = hatX, hatY = hatY,
        )

        if (selectHeld) {
            val dir = when {
                hatX < -0.5f || leftX < -0.5f -> -1
                hatX >  0.5f || leftX >  0.5f ->  1
                else -> 0
            }
            if (dir != 0 && dir != hatChordState) {
                releaseAllKeys(except = setOf("btn_select"))
                val newProfile = viewModel.cycleProfile(
                    forward = dir == 1,
                    keepPressed = setOf("btn_select"),
                )
                loadProfile(newProfile)
            }
            hatChordState = dir
        } else {
            hatChordState = 0
        }
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

    private fun loadProfile(name: String) {
        val controls = profileManager.readControls(name)
        val profileInverse = controls.entries.associate { (prefKey, keyCode) -> keyCode to prefKey }
        profileMap = AndroidGamepadKeyNames.DEFAULT_KEY_MAP + profileInverse
    }

    private fun initProfiles() {
        val profiles = profileManager.listProfiles()
        val initial = profiles.firstOrNull() ?: ProfileManager.NAVIGATION
        viewModel.setProfiles(profiles, initial)
        loadProfile(initial)
        pressedKeycodes.clear()
        selectHeld = false
        startHeld = false
        exitHandler.removeCallbacks(exitRunnable)
    }

    private fun releaseAllKeys(except: Set<String> = emptySet()) {
        val snapshot = pressedKeycodes.toMap()
        for ((kc, resolved) in snapshot) {
            if (resolved in except) continue
            val keyName = KeyEvent.keyCodeToString(kc).removePrefix("KEYCODE_")
            viewModel.onKeyUp(0, kc, keyName, -1, "", resolved)
            pressedKeycodes.remove(kc)
        }
    }

    private fun templateNavButtonFor(template: DeviceTemplate?, keyCode: Int): String? {
        val canonical = template?.bindings?.entries?.firstOrNull { (_, bindings) ->
            bindings.any { it is InputBinding.Button && it.keyCode == keyCode }
        }?.key ?: return null
        return when (canonical) {
            CanonicalButton.BTN_SOUTH -> "btn_south"
            CanonicalButton.BTN_EAST -> "btn_east"
            CanonicalButton.BTN_WEST -> "btn_west"
            CanonicalButton.BTN_NORTH -> "btn_north"
            CanonicalButton.BTN_L -> "btn_l"
            CanonicalButton.BTN_R -> "btn_r"
            CanonicalButton.BTN_L2 -> "btn_l2"
            CanonicalButton.BTN_R2 -> "btn_r2"
            CanonicalButton.BTN_L3 -> "btn_l3"
            CanonicalButton.BTN_R3 -> "btn_r3"
            CanonicalButton.BTN_START -> "btn_start"
            CanonicalButton.BTN_SELECT -> "btn_select"
            CanonicalButton.BTN_MENU -> "btn_menu"
            CanonicalButton.BTN_UP -> "btn_up"
            CanonicalButton.BTN_DOWN -> "btn_down"
            CanonicalButton.BTN_LEFT -> "btn_left"
            CanonicalButton.BTN_RIGHT -> "btn_right"
        }
    }

    private fun templateTriggerValue(
        template: DeviceTemplate?,
        canonical: CanonicalButton,
        event: MotionEvent,
    ): Float? {
        val axisBinding = template?.bindings?.get(canonical)
            ?.firstNotNullOfOrNull { it as? InputBinding.Axis }
            ?.takeIf { it.analogRole == AnalogRole.DIGITAL_BUTTON }
            ?: return null
        return axisBinding.normalize(event.getAxisValue(axisBinding.axis))
    }

    private fun templateStickValue(
        template: DeviceTemplate?,
        role: AnalogRole,
        event: MotionEvent,
    ): Float? {
        val axisBinding = template?.bindings?.values
            ?.flatten()
            ?.firstNotNullOfOrNull {
                (it as? InputBinding.Axis)?.takeIf { axis -> axis.analogRole == role }
            }
            ?: return null
        val raw = event.getAxisValue(axisBinding.axis)
        val span = axisBinding.activeMax - axisBinding.restingValue
        if (span == 0f) return 0f
        val ratio = (raw - axisBinding.restingValue) / span
        val signed = (ratio * 2f - 1f).coerceIn(-1f, 1f)
        return if (axisBinding.invert) -signed else signed
    }

    private fun refreshPorts() {
        val slots = controllerManager.slots
        val ports = slots.indices.mapNotNull { i ->
            val slot = slots[i] ?: return@mapNotNull null
            DeviceInfo(
                port = i,
                deviceId = controllerManager.getDeviceIdForPort(i) ?: -1,
                name = slot.name,
            )
        }
        viewModel.setConnectedPorts(ports)
    }
}
