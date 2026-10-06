package dev.cannoli.scorza.input

import kotlin.math.abs

class BindingCapture(
    private val timeoutMs: Long? = CAPTURE_TIMEOUT_MS,
    private val settleMs: Long = CAPTURE_WINDOW_MS,
) {

    var clock: () -> Long = { System.currentTimeMillis() }

    sealed interface Outcome {
        data class Captured(val bindings: List<InputBinding>) : Outcome
        data object TimedOut : Outcome
    }

    private var pending: CanonicalButton? = null

    private val carriedOver = mutableSetOf<Int>()
    private var lastAxes: Map<Int, Float> = emptyMap()
    private var startedAtMillis: Long = 0
    private var firstEventAtMillis: Long = -1
    private val capturedKeys = linkedSetOf<Int>()
    private val capturedAxes = linkedMapOf<Int, Float>()

    val isListening: Boolean get() = pending != null
    val canonical: CanonicalButton? get() = pending

    fun observe(axisValues: Map<Int, Float>) {
        lastAxes = axisValues
        if (pending != null) onAxis(axisValues) else carriedOver.removeAll { axis ->
            abs(axisValues[axis] ?: 0f) < AXIS_DETECT_THRESHOLD
        }
    }

    fun start(canonical: CanonicalButton) {
        carriedOver.clear()
        for ((axis, value) in lastAxes) {
            if (abs(value) >= AXIS_DETECT_THRESHOLD) carriedOver.add(axis)
        }
        pending = canonical
        startedAtMillis = clock()
        firstEventAtMillis = -1
        capturedKeys.clear()
        capturedAxes.clear()
    }

    fun cancel() {
        pending = null
        firstEventAtMillis = -1
        capturedKeys.clear()
        capturedAxes.clear()
    }

    fun onKey(keyCode: Int) {
        if (pending == null) return
        if (keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN) return
        if (firstEventAtMillis < 0) firstEventAtMillis = clock()
        capturedKeys.add(keyCode)
    }

    fun onAxis(axisValues: Map<Int, Float>) {
        val canonical = pending ?: return
        if (canonical == CanonicalButton.BTN_MENU) return
        for ((axis, value) in axisValues) {
            if (abs(value) < AXIS_DETECT_THRESHOLD) {
                carriedOver.remove(axis)
                continue
            }
            if (axis in carriedOver) continue
            val prev = capturedAxes[axis] ?: 0f
            if (abs(value) > abs(prev)) capturedAxes[axis] = value
            if (firstEventAtMillis < 0) firstEventAtMillis = clock()
        }
    }

    fun tick(): Outcome? {
        val canonical = pending ?: return null
        val now = clock()
        if (firstEventAtMillis < 0) {
            val limit = timeoutMs ?: return null
            if (now - startedAtMillis < limit) return null
            cancel()
            return Outcome.TimedOut
        }
        if (now - firstEventAtMillis < settleMs) return null
        val bindings = bindingsFor(canonical)
        cancel()
        return Outcome.Captured(bindings)
    }

    private fun bindingsFor(canonical: CanonicalButton): List<InputBinding> {
        if (canonical in STICK_CANONICALS) return stickAxisBindings()

        val bindings = mutableListOf<InputBinding>()
        for (key in capturedKeys) bindings.add(InputBinding.Button(key))
        for ((axis, peak) in capturedAxes) {
            val isHatLike = (axis == 15 || axis == 16) && (peak == -1f || peak == 1f)
            if (isHatLike) {
                val direction = when {
                    axis == 15 && peak < 0 -> HatDirection.LEFT
                    axis == 15 && peak > 0 -> HatDirection.RIGHT
                    axis == 16 && peak < 0 -> HatDirection.UP
                    else -> HatDirection.DOWN
                }
                bindings.add(InputBinding.Hat(axis = axis, direction = direction))
            } else {
                val activeMax = if (peak >= 0) 1f else -1f
                bindings.add(
                    InputBinding.Axis(
                        axis = axis,
                        restingValue = 0f,
                        activeMin = 0f,
                        activeMax = activeMax,
                        digitalThreshold = 0.5f,
                        invert = false,
                        analogRole = AnalogRole.DIGITAL_BUTTON,
                    )
                )
            }
        }
        return bindings
    }

    private fun stickAxisBindings(): List<InputBinding> {
        val axis = capturedAxes.maxByOrNull { (_, peak) -> abs(peak) }?.key ?: return emptyList()
        return listOf(
            InputBinding.Axis(
                axis = axis,
                restingValue = -1f,
                activeMin = 0f,
                activeMax = 1f,
                digitalThreshold = 0.5f,
                analogRole = AnalogRole.ANALOG_STICK,
            ),
            InputBinding.Axis(
                axis = axis,
                restingValue = 1f,
                activeMin = 0f,
                activeMax = -1f,
                digitalThreshold = 0.5f,
                analogRole = AnalogRole.ANALOG_STICK,
            ),
        )
    }

    companion object {
        const val CAPTURE_WINDOW_MS = 150L

        const val WIZARD_SETTLE_MS = 60L
        const val CAPTURE_TIMEOUT_MS = 5000L
        private const val AXIS_DETECT_THRESHOLD = 0.6f

        private val STICK_CANONICALS: Set<CanonicalButton> = setOf(
            CanonicalButton.BTN_LSTICK_X,
            CanonicalButton.BTN_LSTICK_Y,
            CanonicalButton.BTN_RSTICK_X,
            CanonicalButton.BTN_RSTICK_Y,
        )
    }
}
