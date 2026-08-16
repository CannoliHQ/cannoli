package dev.cannoli.scorza.input.legend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val CONFIRM_PRESSES_REQUIRED = 3

/**
 * How long a partial run survives without a press. The counter itself is time-free; whoever drives
 * it waits this long on a non-zero count and calls [ConfirmPressCounter.reset], so a run has to be
 * three presses in quick succession rather than three presses spread over a minute.
 */
const val CONFIRM_RUN_TIMEOUT_MS = 1500L

/** How long the expired pips take to fade out. Held solid for the timeout above, then drained. */
const val CONFIRM_RUN_FADE_MS = 300

/**
 * How long a completed run stays on screen before the step leaves. Without it the press that fills
 * the last pip is the same press that navigates, so the run reads as two pips and a page change.
 */
const val CONFIRM_RUN_COMPLETE_HOLD_MS = 220L

/**
 * First run asks for a run of presses of one button on one pad before it judges the mapping. A
 * press of anything else empties the run rather than failing it, and does not start one of its own:
 * the pips all clear, and the press after that begins a fresh run. So three presses of a button
 * that is not confirm still complete, which is what sends a wrongly-mapped pad to the wizard.
 *
 * The device is part of a run's identity because the step exists to establish which pad the player
 * is holding, and two pads pressing the same button are not one player pressing it twice.
 */
class ConfirmPressCounter {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    private var runDeviceId: Int? = null
    private var runKeyCode: Int? = null

    /** True when this press completed a run of [CONFIRM_PRESSES_REQUIRED]. */
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
