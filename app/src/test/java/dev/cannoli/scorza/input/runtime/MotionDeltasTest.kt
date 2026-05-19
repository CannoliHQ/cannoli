package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDeltasTest {

    @Test
    fun empty_deltas_is_not_a_digital_change() {
        assertFalse(MotionDeltas.anyDigitalChange(emptyList()))
    }

    @Test
    fun analog_changed_only_is_not_a_digital_change() {
        val deltas = listOf<CanonicalEvent>(
            CanonicalEvent.AnalogChanged(AnalogRole.LEFT_STICK_X, 0.5f),
            CanonicalEvent.AnalogChanged(AnalogRole.LEFT_STICK_Y, -0.5f),
        )
        // Bound-stick + unbound-HAT corner case: AnalogChanged from a bound stick must not
        // block ViewRootImpl's HAT-to-keycode synthesis.
        assertFalse(MotionDeltas.anyDigitalChange(deltas))
    }

    @Test
    fun pressed_delta_is_a_digital_change() {
        val deltas = listOf<CanonicalEvent>(CanonicalEvent.Pressed(CanonicalButton.BTN_UP))
        assertTrue(MotionDeltas.anyDigitalChange(deltas))
    }

    @Test
    fun released_delta_is_a_digital_change() {
        val deltas = listOf<CanonicalEvent>(CanonicalEvent.Released(CanonicalButton.BTN_DOWN))
        assertTrue(MotionDeltas.anyDigitalChange(deltas))
    }

    @Test
    fun mixed_analog_and_pressed_is_a_digital_change() {
        val deltas = listOf<CanonicalEvent>(
            CanonicalEvent.AnalogChanged(AnalogRole.LEFT_STICK_X, 0.5f),
            CanonicalEvent.Pressed(CanonicalButton.BTN_UP),
        )
        assertTrue(MotionDeltas.anyDigitalChange(deltas))
    }
}
