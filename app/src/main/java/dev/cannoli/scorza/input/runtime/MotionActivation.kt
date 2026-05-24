package dev.cannoli.scorza.input.runtime

import android.view.MotionEvent
import kotlin.math.abs

object MotionActivation {

    private const val STICK_DEADZONE = 0.3f

    private val ACTIVATING_AXES = setOf(
        MotionEvent.AXIS_X,
        MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RZ,
    )

    fun shouldActivate(axisValues: Map<Int, Float>): Boolean {
        for ((axis, value) in axisValues) {
            if (axis !in ACTIVATING_AXES) continue
            if (abs(value) > STICK_DEADZONE) return true
        }
        return false
    }

    fun shouldActivate(event: MotionEvent): Boolean {
        for (axis in ACTIVATING_AXES) {
            if (abs(event.getAxisValue(axis)) > STICK_DEADZONE) return true
        }
        return false
    }
}
