package dev.cannoli.scorza.ui.viewmodel

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class InputTesterState(
    val pressedButtons: Set<String> = emptySet(),
    val leftStick: Offset = Offset.Zero,
    val rightStick: Offset = Offset.Zero,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val firstPressedAtMs: Long? = null,
)

data class EventLogEntry(
    val keyCode: Int,
    val keyName: String,
    val deviceId: Int,
    val deviceName: String,
    val resolvedButton: String?,
    val timestamp: Long,
    val isDown: Boolean = true,
)

data class DeviceInfo(
    val port: Int,
    val deviceId: Int,
    val name: String,
)

data class InputTesterUiState(
    val activePort: Int = 0,
    val portStates: Map<Int, InputTesterState> = emptyMap(),
    val connectedPorts: List<DeviceInfo> = emptyList(),
    val lastEventDevice: DeviceInfo? = null,
    val eventLog: List<EventLogEntry> = emptyList(),
    val exitRequested: Boolean = false,
    val availableProfiles: List<String> = emptyList(),
    val selectedProfile: String = "",
)

class InputTesterViewModel(
    private val now: () -> Long = System::currentTimeMillis,
    private val eventLogCapacity: Int = DEFAULT_EVENT_LOG_CAPACITY,
) {
    private val _state = MutableStateFlow(InputTesterUiState())
    val state: StateFlow<InputTesterUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = InputTesterUiState()
        heldKeyCodes.clear()
        repeatKeyCode = -1
        repeatCount = 0
    }

    fun setProfiles(available: List<String>, selected: String) {
        _state.update { it.copy(availableProfiles = available, selectedProfile = selected) }
    }

    fun cycleProfile(forward: Boolean, keepPressed: Set<String> = emptySet()): String {
        var result = _state.value.selectedProfile
        _state.update { current ->
            val list = current.availableProfiles
            if (list.isEmpty()) return@update current
            val idx = list.indexOf(current.selectedProfile).let { if (it < 0) 0 else it }
            val step = if (forward) 1 else -1
            val next = list[((idx + step) + list.size) % list.size]
            result = next
            val clearedPorts = current.portStates.mapValues { (_, s) ->
                s.copy(
                    pressedButtons = s.pressedButtons intersect keepPressed,
                    firstPressedAtMs = if ((s.pressedButtons intersect keepPressed).isNotEmpty()) s.firstPressedAtMs else null,
                )
            }
            current.copy(selectedProfile = next, portStates = clearedPorts)
        }
        return result
    }

    private var repeatKeyCode: Int = -1
    private var repeatCount: Int = 0
    private val heldKeyCodes = mutableSetOf<Int>()

    fun onKeyDown(
        port: Int,
        keyCode: Int,
        keyName: String,
        deviceId: Int,
        deviceName: String,
        resolvedButton: String?,
    ) {
        val isFreshPress = heldKeyCodes.add(keyCode)
        val ts = now()
        val shouldExit: Boolean
        if (isFreshPress) {
            if (keyCode == repeatKeyCode) repeatCount++ else { repeatKeyCode = keyCode; repeatCount = 1 }
            shouldExit = repeatCount >= EXIT_REPEAT_COUNT
        } else {
            shouldExit = false
        }
        _state.update { current ->
            val prev = current.portStates[port] ?: InputTesterState()
            val pressed = if (resolvedButton != null) prev.pressedButtons + resolvedButton else prev.pressedButtons
            val updatedPort = prev.copy(pressedButtons = pressed)
            val entry = EventLogEntry(keyCode, keyName, deviceId, deviceName, resolvedButton, ts, isDown = true)
            current.copy(
                portStates = current.portStates + (port to updatedPort),
                lastEventDevice = DeviceInfo(port, deviceId, deviceName),
                eventLog = if (isFreshPress) (listOf(entry) + current.eventLog).take(eventLogCapacity) else current.eventLog,
                exitRequested = current.exitRequested || shouldExit,
            )
        }
    }

    fun onKeyUp(
        port: Int,
        keyCode: Int,
        keyName: String,
        deviceId: Int,
        deviceName: String,
        resolvedButton: String?,
    ) {
        heldKeyCodes.remove(keyCode)
        val ts = now()
        _state.update { current ->
            val prev = current.portStates[port] ?: InputTesterState()
            val pressed = if (resolvedButton != null) prev.pressedButtons - resolvedButton else prev.pressedButtons
            val updatedPort = prev.copy(pressedButtons = pressed)
            val entry = EventLogEntry(keyCode, keyName, deviceId, deviceName, resolvedButton, ts, isDown = false)
            current.copy(
                portStates = current.portStates + (port to updatedPort),
                eventLog = (listOf(entry) + current.eventLog).take(eventLogCapacity),
            )
        }
    }

    fun onMotion(
        port: Int,
        deviceId: Int,
        deviceName: String,
        leftX: Float, leftY: Float,
        rightX: Float, rightY: Float,
        leftTrigger: Float, rightTrigger: Float,
        hatX: Float, hatY: Float,
    ) {
        val ts = now()
        _state.update { current ->
            val prev = current.portStates[port] ?: InputTesterState()

            val hatButtons = buildSet {
                if (hatX < -0.5f) add("btn_left")
                if (hatX > 0.5f) add("btn_right")
                if (hatY < -0.5f) add("btn_up")
                if (hatY > 0.5f) add("btn_down")
            }
            val triggerButtons = buildSet {
                if (leftTrigger > 0.5f) add("btn_l2")
                if (rightTrigger > 0.5f) add("btn_r2")
            }
            val axisPressed = hatButtons + triggerButtons

            val nonAxis = prev.pressedButtons - HAT_BUTTONS - TRIGGER_BUTTONS
            val newPressed = nonAxis + axisPressed

            val newlyPressed = axisPressed - (prev.pressedButtons intersect HAT_BUTTONS) - (prev.pressedButtons intersect TRIGGER_BUTTONS)
            val synthLogEntries = newlyPressed.map { btn ->
                EventLogEntry(
                    keyCode = -1,
                    keyName = btn.removePrefix("btn_").uppercase(),
                    deviceId = deviceId,
                    deviceName = deviceName,
                    resolvedButton = btn,
                    timestamp = ts,
                )
            }
            val updatedLog = if (synthLogEntries.isEmpty()) current.eventLog
                else (synthLogEntries + current.eventLog).take(eventLogCapacity)

            val anyActivity = newPressed.isNotEmpty() || leftX != 0f || leftY != 0f ||
                    rightX != 0f || rightY != 0f || leftTrigger > 0f || rightTrigger > 0f

            val updatedPort = prev.copy(
                pressedButtons = newPressed,
                leftStick = Offset(leftX, leftY),
                rightStick = Offset(rightX, rightY),
                leftTrigger = leftTrigger,
                rightTrigger = rightTrigger,
                firstPressedAtMs = when {
                    !anyActivity -> null
                    prev.firstPressedAtMs != null -> prev.firstPressedAtMs
                    else -> ts
                },
            )
            current.copy(
                portStates = current.portStates + (port to updatedPort),
                lastEventDevice = if (anyActivity) DeviceInfo(port, deviceId, deviceName) else current.lastEventDevice,
                eventLog = updatedLog,
            )
        }
    }

    fun setConnectedPorts(ports: List<DeviceInfo>) {
        _state.update { current ->
            val stillValid = ports.any { it.port == current.activePort }
            val newActive = if (stillValid) current.activePort else ports.firstOrNull()?.port ?: 0
            current.copy(connectedPorts = ports, activePort = newActive)
        }
    }

    fun cycleActivePort(forward: Boolean) {
        _state.update { current ->
            if (current.connectedPorts.isEmpty()) return@update current
            val idx = current.connectedPorts.indexOfFirst { it.port == current.activePort }
            val step = if (forward) 1 else -1
            val nextIdx = ((idx + step) + current.connectedPorts.size) % current.connectedPorts.size
            current.copy(activePort = current.connectedPorts[nextIdx].port)
        }
    }

    fun setActivePort(port: Int) {
        _state.update { current ->
            if (current.activePort == port) current
            else current.copy(activePort = port)
        }
    }

    companion object {
        const val EXIT_REPEAT_COUNT = 10
        const val DEFAULT_EVENT_LOG_CAPACITY = 8
        private val HAT_BUTTONS = setOf("btn_up", "btn_down", "btn_left", "btn_right")
        private val TRIGGER_BUTTONS = setOf("btn_l2", "btn_r2")
    }
}
