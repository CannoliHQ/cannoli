package dev.cannoli.scorza.input

import dev.cannoli.igm.CanonicalButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val HAT_X = 15
private const val HAT_Y = 16

class InputTesterHatTest {

    private fun mapping(bindings: Map<CanonicalButton, List<InputBinding>>) = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
        ),
        bindings = bindings,
        source = MappingSource.BUNDLED,
    )

    private fun hat(direction: HatDirection, axis: Int = HAT_X) =
        listOf(InputBinding.Hat(axis, direction) as InputBinding)

    private val straight = mapping(mapOf(
        CanonicalButton.BTN_LEFT to hat(HatDirection.LEFT),
        CanonicalButton.BTN_RIGHT to hat(HatDirection.RIGHT),
        CanonicalButton.BTN_UP to hat(HatDirection.UP, HAT_Y),
        CanonicalButton.BTN_DOWN to hat(HatDirection.DOWN, HAT_Y),
    ))

    // The AYN Thor shipped with exactly this: left bound to hat right and vice versa.
    private val crossed = mapping(mapOf(
        CanonicalButton.BTN_LEFT to hat(HatDirection.RIGHT),
        CanonicalButton.BTN_RIGHT to hat(HatDirection.LEFT),
    ))

    private fun axes(vararg values: Pair<Int, Float>): (Int) -> Float {
        val map = values.toMap()
        return { map[it] ?: 0f }
    }

    @Test fun `a straight mapping reports the direction pressed`() {
        assertEquals(setOf("btn_left"), mappingHatButtons(straight, axes(HAT_X to -1f)))
        assertEquals(setOf("btn_right"), mappingHatButtons(straight, axes(HAT_X to 1f)))
        assertEquals(setOf("btn_up"), mappingHatButtons(straight, axes(HAT_Y to -1f)))
        assertEquals(setOf("btn_down"), mappingHatButtons(straight, axes(HAT_Y to 1f)))
    }

    @Test fun `a crossed mapping reports the binding, not the hardware`() {
        assertEquals(setOf("btn_right"), mappingHatButtons(crossed, axes(HAT_X to -1f)))
        assertEquals(setOf("btn_left"), mappingHatButtons(crossed, axes(HAT_X to 1f)))
    }

    @Test fun `a resting hat reports nothing`() {
        assertEquals(emptySet<String>(), mappingHatButtons(straight, axes(HAT_X to 0f)))
    }

    @Test fun `a mapping with no hat bindings falls back to the raw hat`() {
        assertNull(mappingHatButtons(null, axes(HAT_X to -1f)))
        val keycodeDpad = mapping(mapOf(CanonicalButton.BTN_LEFT to listOf(InputBinding.Button(21))))
        assertNull(mappingHatButtons(keycodeDpad, axes(HAT_X to -1f)))
    }

    @Test fun `the raw fallback reads the hat sign`() {
        assertEquals(setOf("btn_left"), rawHatButtons(-1f, 0f))
        assertEquals(setOf("btn_right"), rawHatButtons(1f, 0f))
        assertEquals(setOf("btn_up"), rawHatButtons(0f, -1f))
        assertEquals(setOf("btn_down"), rawHatButtons(0f, 1f))
        assertEquals(emptySet<String>(), rawHatButtons(0f, 0f))
    }
}
