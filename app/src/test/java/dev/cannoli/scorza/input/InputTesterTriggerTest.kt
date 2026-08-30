package dev.cannoli.scorza.input

import dev.cannoli.igm.CanonicalButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LTRIGGER = 17
private const val BRAKE = 23

class InputTesterTriggerTest {

    private fun mapping(bindings: Map<CanonicalButton, List<InputBinding>>) = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(name = "Test Pad", vendorId = 1234, productId = 5678),
        bindings = bindings,
        source = MappingSource.BUNDLED,
    )

    private fun triggerAxis(axis: Int) = InputBinding.Axis(
        axis = axis,
        restingValue = 0f,
        activeMin = 0f,
        activeMax = 1f,
        digitalThreshold = 0.5f,
        analogRole = AnalogRole.DIGITAL_BUTTON,
    )

    private fun l2(vararg bindings: InputBinding) =
        mapping(mapOf(CanonicalButton.BTN_L2 to bindings.toList()))

    private fun axes(vararg pulled: Int): (Int) -> Float = { if (it in pulled) 1f else 0f }

    private fun unbound(mapping: DeviceMapping?, pulled: (Int) -> Float) =
        triggerUnbound(mapping, CanonicalButton.BTN_L2, pulled)

    @Test fun `the bound axis moving is the quiet case`() {
        val m = l2(InputBinding.Button(104), triggerAxis(LTRIGGER))
        assertFalse(unbound(m) { if (it == LTRIGGER) 1f else 0f })
    }

    // The defect the flag exists for: the tester's bar fills off the raw axis either way, so
    // nothing else on this screen says the mapping missed it.
    @Test fun `an axis the mapping does not name is flagged`() {
        val m = l2(InputBinding.Button(104), triggerAxis(LTRIGGER))
        assertTrue(unbound(m, axes(BRAKE)))
    }

    @Test fun `a pad reporting one trigger on both axes stays quiet`() {
        val m = l2(InputBinding.Button(104), triggerAxis(LTRIGGER))
        assertFalse(unbound(m, axes(LTRIGGER, BRAKE)))
    }

    // A digital trigger arrives as a keycode and moves no axis at all, so there is nothing to flag.
    @Test fun `a keycode only trigger with no axis movement stays quiet`() {
        assertFalse(unbound(l2(InputBinding.Button(104)), axes()))
    }

    @Test fun `a keycode only trigger on an axis reporting pad is flagged`() {
        assertTrue(unbound(l2(InputBinding.Button(104)), axes(LTRIGGER)))
    }

    @Test fun `a canonical carrying nothing at all is unbound`() {
        assertTrue(unbound(mapping(emptyMap()), axes()))
        assertTrue(unbound(null, axes()))
    }

    @Test fun `a resting axis is not movement`() {
        val m = l2(InputBinding.Button(104), triggerAxis(LTRIGGER))
        assertFalse(unbound(m) { 0.2f })
    }
}
