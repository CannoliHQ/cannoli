package dev.cannoli.scorza.input

import kotlin.math.abs
import kotlin.math.max

enum class DominantAxis { HORIZONTAL, VERTICAL, NONE }

/**
 * Reports which stick axis dominates by magnitude, so the caller can drop the other one and drive a
 * D-pad without ever producing a diagonal. Magnitude is only available here, in the raw motion
 * event: downstream the stick has already become a set of canonical buttons.
 *
 * Returns an enum rather than a resolved vector because this sits on the motion hot path, where a
 * Pair<Float, Float> would box both floats on every event.
 */
class StickDominance {

    private val latched = mutableMapOf<Int, DominantAxis>()

    fun dominantAxis(deviceId: Int, x: Float, y: Float): DominantAxis {
        val current = latched[deviceId]
        val heldValue = when (current) {
            DominantAxis.HORIZONTAL -> abs(x)
            DominantAxis.VERTICAL -> abs(y)
            else -> 0f
        }
        // Hold the winner until it falls below the release threshold. Without this the axis flips
        // back and forth on every motion event near 45 degrees.
        if (current != null && heldValue >= HatKeys.RELEASE_THRESHOLD) return current
        latched.remove(deviceId)
        if (max(abs(x), abs(y)) < HatKeys.PRESS_THRESHOLD) return DominantAxis.NONE
        val winner = if (abs(x) >= abs(y)) DominantAxis.HORIZONTAL else DominantAxis.VERTICAL
        latched[deviceId] = winner
        return winner
    }

    fun reset() {
        latched.clear()
    }
}
