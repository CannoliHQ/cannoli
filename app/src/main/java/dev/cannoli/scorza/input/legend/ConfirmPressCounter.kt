package dev.cannoli.scorza.input.legend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val CONFIRM_PRESSES_REQUIRED = 3

const val CONFIRM_RUN_TIMEOUT_MS = 1500L

const val CONFIRM_RUN_FADE_MS = 300

const val CONFIRM_RUN_COMPLETE_HOLD_MS = 220L

class ConfirmPressCounter {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    private var runDeviceId: Int? = null
    private var runKeyCode: Int? = null

    fun press(deviceId: Int, keyCode: Int): Boolean {
        when {
            _count.value == 0 -> {
                runDeviceId = deviceId
                runKeyCode = keyCode
                _count.value = 1
            }
            deviceId != runDeviceId || keyCode != runKeyCode -> {
                reset()
                return false
            }
            else -> _count.value += 1
        }
        return _count.value >= CONFIRM_PRESSES_REQUIRED
    }

    fun reset() {
        runDeviceId = null
        runKeyCode = null
        _count.value = 0
    }
}
