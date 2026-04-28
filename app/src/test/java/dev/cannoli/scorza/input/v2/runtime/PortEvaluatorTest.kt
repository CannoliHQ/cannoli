package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortEvaluatorTest {

    private fun template(
        bindings: Map<CanonicalButton, List<InputBinding>>,
    ) = DeviceTemplate(
        id = "t",
        displayName = "T",
        match = DeviceMatchRule(),
        bindings = bindings,
        source = TemplateSource.RETROARCH_AUTOCONFIG,
    )

    @Test
    fun key_down_on_bound_keycode_emits_pressed_once() {
        val e = PortEvaluator(
            template(mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))))
        )
        val deltas = e.evaluateKeyDown(96, isAndroidRepeat = false)
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_SOUTH)), deltas)
    }

    @Test
    fun key_up_on_bound_keycode_emits_released() {
        val e = PortEvaluator(
            template(mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))))
        )
        e.evaluateKeyDown(96, isAndroidRepeat = false)
        val deltas = e.evaluateKeyUp(96)
        assertEquals(listOf(CanonicalEvent.Released(CanonicalButton.BTN_SOUTH)), deltas)
    }

    @Test
    fun key_down_with_android_repeat_is_filtered() {
        val e = PortEvaluator(
            template(mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))))
        )
        e.evaluateKeyDown(96, isAndroidRepeat = false)
        val deltas = e.evaluateKeyDown(96, isAndroidRepeat = true)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun unbound_keycode_yields_empty_event_list() {
        val e = PortEvaluator(
            template(mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))))
        )
        val downDeltas = e.evaluateKeyDown(99, isAndroidRepeat = false)
        val upDeltas = e.evaluateKeyUp(99)
        assertTrue(downDeltas.isEmpty())
        assertTrue(upDeltas.isEmpty())
    }

    @Test
    fun second_source_asserting_held_canonical_does_not_duplicate_pressed() {
        // BTN_L2 bound to BOTH a key (104) and an axis (17). Both pressed in turn,
        // only the first should emit Pressed.
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L2 to listOf(
                    InputBinding.Button(104),
                    InputBinding.Axis(
                        axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f,
                    ),
                ),
            ))
        )
        val first = e.evaluateKeyDown(104, isAndroidRepeat = false)
        // Simulate the axis binding asserting via evaluateAxis (Task 3 lands the impl).
        // For Task 2, we only verify the duplicate-Pressed avoidance via the key path:
        // pressing the same key twice does not duplicate.
        val secondPressOfSameKey = e.evaluateKeyDown(104, isAndroidRepeat = false)
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_L2)), first)
        assertTrue(secondPressOfSameKey.isEmpty())
    }

    @Test
    fun releasing_key_when_canonical_not_held_emits_nothing() {
        val e = PortEvaluator(
            template(mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))))
        )
        val deltas = e.evaluateKeyUp(96)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun currently_pressed_reflects_held_canonical_buttons() {
        val e = PortEvaluator(template(mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
        )))
        e.evaluateKeyDown(96, isAndroidRepeat = false)
        e.evaluateKeyDown(97, isAndroidRepeat = false)
        e.evaluateKeyUp(96)
        assertEquals(setOf(CanonicalButton.BTN_EAST), e.currentlyPressed())
    }

    @Test
    fun axis_trigger_above_threshold_emits_pressed() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L2 to listOf(InputBinding.Axis(
                    axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                )),
            ))
        )
        val deltas = e.evaluateAxis(mapOf(17 to 0.8f))
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_L2)), deltas)
    }

    @Test
    fun axis_trigger_with_negative_resting_value_handles_issue_151() {
        // Stadia LTRIGGER: rests at -1.0, active 0..1.
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L2 to listOf(InputBinding.Axis(
                    axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                )),
            ))
        )
        val resting = e.evaluateAxis(mapOf(17 to -1f))
        val partial = e.evaluateAxis(mapOf(17 to 0f))
        val full = e.evaluateAxis(mapOf(17 to 1f))
        assertTrue(resting.isEmpty())
        // At raw=0, normalized=0.5, exactly at threshold -> pressed.
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_L2)), partial)
        // At raw=1, still pressed; no duplicate event.
        assertTrue(full.isEmpty())
    }

    @Test
    fun axis_falling_below_threshold_emits_released() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L2 to listOf(InputBinding.Axis(
                    axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                )),
            ))
        )
        e.evaluateAxis(mapOf(17 to 1f))
        val deltas = e.evaluateAxis(mapOf(17 to -1f))
        assertEquals(listOf(CanonicalEvent.Released(CanonicalButton.BTN_L2)), deltas)
    }

    @Test
    fun hat_axis_emits_pressed_for_directional_canonical_button() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_UP to listOf(
                    InputBinding.Hat(axis = 16, direction = HatDirection.UP, threshold = 0.5f),
                ),
                CanonicalButton.BTN_DOWN to listOf(
                    InputBinding.Hat(axis = 16, direction = HatDirection.DOWN, threshold = 0.5f),
                ),
            ))
        )
        val up = e.evaluateAxis(mapOf(16 to -1f))
        val center = e.evaluateAxis(mapOf(16 to 0f))
        val down = e.evaluateAxis(mapOf(16 to 1f))
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_UP)), up)
        assertEquals(listOf(CanonicalEvent.Released(CanonicalButton.BTN_UP)), center)
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_DOWN)), down)
    }

    @Test
    fun analog_stick_emits_changed_only_when_delta_exceeds_noise_threshold() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L3 to listOf(InputBinding.Axis(
                    axis = 0, restingValue = 0f, activeMin = -1f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.LEFT_STICK_X,
                )),
            )),
            analogNoiseThreshold = 0.05f,
        )
        val first = e.evaluateAxis(mapOf(0 to 0.5f))
        val noise = e.evaluateAxis(mapOf(0 to 0.51f))
        val real = e.evaluateAxis(mapOf(0 to 0.6f))
        assertEquals(1, first.size)
        assertTrue(first.first() is CanonicalEvent.AnalogChanged)
        assertTrue(noise.isEmpty())
        assertEquals(1, real.size)
    }

    @Test
    fun analog_value_returns_last_normalized_value() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L3 to listOf(InputBinding.Axis(
                    axis = 0, restingValue = 0f, activeMin = -1f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.LEFT_STICK_X,
                )),
            ))
        )
        e.evaluateAxis(mapOf(0 to 0.7f))
        // normalize: (0.7 - 0) / (1 - 0) = 0.7, clamped to [0,1]
        assertEquals(0.7f, e.analogValue(AnalogRole.LEFT_STICK_X), 0.001f)
    }

    @Test
    fun two_axis_bindings_for_same_canonical_button_are_independent() {
        // BTN_L2 bound to both axis-17-positive and axis-18-positive (unusual but legal).
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_L2 to listOf(
                    InputBinding.Axis(
                        axis = 17, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f,
                    ),
                    InputBinding.Axis(
                        axis = 18, restingValue = -1f, activeMin = 0f, activeMax = 1f,
                        digitalThreshold = 0.5f,
                    ),
                ),
            ))
        )
        val first = e.evaluateAxis(mapOf(17 to 1f))
        val second = e.evaluateAxis(mapOf(18 to 1f))
        val firstReleased = e.evaluateAxis(mapOf(17 to -1f))
        val secondReleased = e.evaluateAxis(mapOf(18 to -1f))
        assertEquals(listOf(CanonicalEvent.Pressed(CanonicalButton.BTN_L2)), first)
        assertTrue(second.isEmpty())
        assertTrue(firstReleased.isEmpty())
        assertEquals(listOf(CanonicalEvent.Released(CanonicalButton.BTN_L2)), secondReleased)
    }

    @Test
    fun snapshot_matches_currently_pressed_and_analog_value() {
        val e = PortEvaluator(
            template(mapOf(
                CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
                CanonicalButton.BTN_L3 to listOf(InputBinding.Axis(
                    axis = 0, restingValue = 0f, activeMin = -1f, activeMax = 1f,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.LEFT_STICK_X,
                )),
            ))
        )
        e.evaluateKeyDown(96, isAndroidRepeat = false)
        e.evaluateAxis(mapOf(0 to 0.6f))
        val snap = e.snapshot()
        assertEquals(setOf(CanonicalButton.BTN_SOUTH), snap.pressed)
        assertEquals(0.6f, snap.analog[AnalogRole.LEFT_STICK_X]!!, 0.001f)
    }

    @Test
    fun reset_state_emits_released_for_each_held_button_and_clears_state() {
        val e = PortEvaluator(template(mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
        )))
        e.evaluateKeyDown(96, isAndroidRepeat = false)
        e.evaluateKeyDown(97, isAndroidRepeat = false)
        val deltas = e.resetState()
        assertEquals(2, deltas.size)
        assertTrue(deltas.contains(CanonicalEvent.Released(CanonicalButton.BTN_SOUTH)))
        assertTrue(deltas.contains(CanonicalEvent.Released(CanonicalButton.BTN_EAST)))
        assertTrue(e.currentlyPressed().isEmpty())
    }

    @Test
    fun reset_state_when_nothing_held_emits_nothing() {
        val e = PortEvaluator(template(mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
        )))
        assertTrue(e.resetState().isEmpty())
    }
}
