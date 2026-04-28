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
}
