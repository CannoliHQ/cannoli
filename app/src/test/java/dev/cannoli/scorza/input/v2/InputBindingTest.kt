package dev.cannoli.scorza.input.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputBindingTest {

    @Test
    fun axis_normalization_resting_at_zero() {
        val a = InputBinding.Axis(
            axis = 17, restingValue = 0f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f
        )
        assertEquals(0f, a.normalize(0f), 0.001f)
        assertEquals(1f, a.normalize(1f), 0.001f)
        assertEquals(0.5f, a.normalize(0.5f), 0.001f)
    }

    @Test
    fun axis_normalization_with_negative_resting_value() {
        // Stadia LTRIGGER: rests at -1, active 0..1.
        val a = InputBinding.Axis(
            axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f
        )
        assertEquals(0f, a.normalize(-1f), 0.001f)
        assertEquals(0.5f, a.normalize(0f), 0.001f)
        assertEquals(1f, a.normalize(1f), 0.001f)
    }

    @Test
    fun axis_normalization_clamps_outside_range() {
        val a = InputBinding.Axis(
            axis = 17, restingValue = 0f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f
        )
        assertEquals(0f, a.normalize(-0.5f), 0.001f)
        assertEquals(1f, a.normalize(1.5f), 0.001f)
    }

    @Test
    fun axis_inversion_flips_normalized_value() {
        val a = InputBinding.Axis(
            axis = 17, restingValue = 0f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f, invert = true
        )
        assertEquals(1f, a.normalize(0f), 0.001f)
        assertEquals(0f, a.normalize(1f), 0.001f)
    }

    @Test
    fun axis_digital_pressed_at_or_above_threshold() {
        val a = InputBinding.Axis(
            axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
            digitalThreshold = 0.5f
        )
        assertFalse(a.isDigitalPressed(-1f))
        assertFalse(a.isDigitalPressed(-0.1f))
        assertTrue(a.isDigitalPressed(0.5f))
        assertTrue(a.isDigitalPressed(1f))
    }

    @Test
    fun hat_pressed_when_axis_value_meets_threshold_for_direction() {
        val up = InputBinding.Hat(axis = 0, direction = HatDirection.UP, threshold = 0.5f)
        assertTrue(up.isPressed(-1f))
        assertFalse(up.isPressed(0f))
        val down = InputBinding.Hat(axis = 0, direction = HatDirection.DOWN, threshold = 0.5f)
        assertTrue(down.isPressed(1f))
        assertFalse(down.isPressed(-1f))
        val left = InputBinding.Hat(axis = 1, direction = HatDirection.LEFT, threshold = 0.5f)
        assertTrue(left.isPressed(-1f))
        val right = InputBinding.Hat(axis = 1, direction = HatDirection.RIGHT, threshold = 0.5f)
        assertTrue(right.isPressed(1f))
    }

    @Test
    fun button_binding_holds_keycode() {
        val b = InputBinding.Button(keyCode = 96)
        assertEquals(96, b.keyCode)
    }
}
