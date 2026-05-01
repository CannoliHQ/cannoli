package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMapping
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
    private val axisTriggerL2Held = mutableSetOf<Int>()
    private val axisTriggerR2Held = mutableSetOf<Int>()
    private val exitHandler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { viewModel.requestExit() }

    fun enter() {
        portRouter.resetAllEvaluators()
        viewModel.reset()
        initProfiles()
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
            if (!isRepeat && selectHeld && navButton == "btn_north") {
                viewModel.toggleAxisDump()
            }
            val resolved = mappingNav ?: profileMap[event.keyCode]
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
        val port = portRouter.portFor(deviceId) ?: 0
        val mapping = portRouter.mappingForPort(port)
        val name = mapping?.displayName?.takeIf { it.isNotEmpty() }
            ?: event.device?.name?.takeIf { it.isNotEmpty() }
            ?: unknownDeviceName
        val leftX = mostActive(mappingStickValue(mapping, AnalogRole.LEFT_STICK_X, event), event.getAxisValue(MotionEvent.AXIS_X))
        val leftY = mostActive(mappingStickValue(mapping, AnalogRole.LEFT_STICK_Y, event), event.getAxisValue(MotionEvent.AXIS_Y))
        val rightX = mostActive(mappingStickValue(mapping, AnalogRole.RIGHT_STICK_X, event), event.getAxisValue(MotionEvent.AXIS_Z))
        val rightY = mostActive(mappingStickValue(mapping, AnalogRole.RIGHT_STICK_Y, event), event.getAxisValue(MotionEvent.AXIS_RZ))
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
            hatX = hatX, hatY = hatY,
        )

        val activatesPort = leftTrigger > 0.1f || rightTrigger > 0.1f ||
            kotlin.math.abs(hatX) > 0.5f || kotlin.math.abs(hatY) > 0.5f
        if (activatesPort) viewModel.setActivePort(port)

        val dumpAxes = event.device?.motionRanges?.map { it.axis }?.distinct() ?: emptyList()
        viewModel.recordAxisValues(port, dumpAxes.associateWith { event.getAxisValue(it) })

        syncAxisTrigger(port, deviceId, name, KeyEvent.KEYCODE_BUTTON_L2, leftTrigger, axisTriggerL2Held, "btn_l2")
        syncAxisTrigger(port, deviceId, name, KeyEvent.KEYCODE_BUTTON_R2, rightTrigger, axisTriggerR2Held, "btn_r2")

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
        legacyKey: String,
    ) {
        val keyName = KeyEvent.keyCodeToString(syntheticKeyCode).removePrefix("KEYCODE_")
        val wasHeld = deviceId in held
        if (value > 0.5f && !wasHeld) {
            held.add(deviceId)
            viewModel.onKeyDown(port, syntheticKeyCode, keyName, deviceId, deviceName, legacyKey)
        } else if (value < 0.3f && wasHeld) {
            held.remove(deviceId)
            viewModel.onKeyUp(port, syntheticKeyCode, keyName, deviceId, deviceName, legacyKey)
        }
    }

    private fun mappingNavButtonFor(mapping: DeviceMapping?, keyCode: Int): String? {
        val canonical = mapping?.bindings?.entries?.firstOrNull { (_, bindings) ->
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

    private fun mappingTriggerValue(
        mapping: DeviceMapping?,
        canonical: CanonicalButton,
        event: MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.get(canonical)
            ?.firstNotNullOfOrNull { it as? InputBinding.Axis }
            ?.takeIf { it.analogRole == AnalogRole.DIGITAL_BUTTON }
            ?: return null
        return axisBinding.normalize(event.getAxisValue(axisBinding.axis))
    }

    private fun mappingTriggerDisplayValue(
        mapping: DeviceMapping?,
        canonical: CanonicalButton,
        event: MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.get(canonical)
            ?.firstNotNullOfOrNull { it as? InputBinding.Axis }
            ?.takeIf { it.analogRole == AnalogRole.DIGITAL_BUTTON }
            ?: return null
        return event.getAxisValue(axisBinding.axis).coerceIn(0f, 1f)
    }

    private fun mappingStickValue(
        mapping: DeviceMapping?,
        role: AnalogRole,
        event: MotionEvent,
    ): Float? {
        val axisBinding = mapping?.bindings?.values
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
