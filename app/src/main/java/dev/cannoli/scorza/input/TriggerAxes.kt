package dev.cannoli.scorza.input

import android.view.MotionEvent

object TriggerAxes {

    val byButton: Map<CanonicalButton, List<Int>> = mapOf(
        CanonicalButton.BTN_L2 to listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
        CanonicalButton.BTN_R2 to listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
    )

    val all: List<Int> = byButton.values.flatten()
}
