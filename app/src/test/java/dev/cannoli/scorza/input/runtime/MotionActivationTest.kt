package dev.cannoli.scorza.input.runtime

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionActivationTest {

    @Test fun `left stick past deadzone activates`() {
        assertTrue(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_X to 0.5f)))
        assertTrue(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_Y to -0.5f)))
    }

    @Test fun `right stick past deadzone activates`() {
        assertTrue(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_Z to 0.5f)))
        assertTrue(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_RZ to -0.5f)))
    }

    @Test fun `stick at or under deadzone does not activate`() {
        assertFalse(MotionActivation.shouldActivate(emptyMap()))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_X to 0.2f)))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_Y to -0.29f)))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_RZ to 0.3f)))
    }

    @Test fun `non-stick axes do not activate`() {
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_LTRIGGER to 1.0f)))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_RTRIGGER to 1.0f)))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_BRAKE to 1.0f)))
        assertFalse(MotionActivation.shouldActivate(mapOf(MotionEvent.AXIS_GAS to 1.0f)))
    }

    @Test fun `any one stick axis past deadzone is enough`() {
        val values = mapOf(
            MotionEvent.AXIS_X to 0.1f,
            MotionEvent.AXIS_Y to 0.1f,
            MotionEvent.AXIS_Z to 0.4f,
            MotionEvent.AXIS_RZ to 0.0f,
        )
        assertTrue(MotionActivation.shouldActivate(values))
    }
}
