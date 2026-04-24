package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import dev.cannoli.scorza.ui.viewmodel.DeviceInfo
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel

class InputTesterController(
    private val viewModel: InputTesterViewModel,
    private val controllerManager: ControllerManager,
    private val profileManager: ProfileManager,
    private val inputHandler: InputHandler,
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
        val navButton = inputHandler.resolveButton(event)

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
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
        val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)
        val leftTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        val rightTrigger = maxOf(
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
        profileMap = InputHandler.DEFAULT_KEY_MAP + profileInverse
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
