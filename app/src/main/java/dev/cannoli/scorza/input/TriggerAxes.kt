package dev.cannoli.scorza.input

import android.view.MotionEvent

/**
 * The axes a pad can report its analog triggers on, in the order a binding prefers them.
 *
 * Android specifies two conventions and a pad picks one, though a few report both. Everything that
 * has to name a trigger axis reads it from here, so the fallback binding, the device probe and the
 * input tester cannot come to disagree about what counts as a trigger.
 */
object TriggerAxes {

    val byButton: Map<CanonicalButton, List<Int>> = mapOf(
        CanonicalButton.BTN_L2 to listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
        CanonicalButton.BTN_R2 to listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
    )

    val all: List<Int> = byButton.values.flatten()
}
