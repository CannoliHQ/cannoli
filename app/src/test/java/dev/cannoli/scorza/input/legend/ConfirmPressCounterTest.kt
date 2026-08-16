package dev.cannoli.scorza.input.legend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAD = 1
private const val OTHER_PAD = 2

class ConfirmPressCounterTest {

    private val counter = ConfirmPressCounter()

    @Test fun `three presses of one button on one pad complete the run`() {
        assertFalse(counter.press(PAD, 96))
        assertEquals(1, counter.count.value)
        assertFalse(counter.press(PAD, 96))
        assertEquals(2, counter.count.value)
        assertTrue(counter.press(PAD, 96))
        assertEquals(CONFIRM_PRESSES_REQUIRED, counter.count.value)
    }

    @Test fun `a different button empties the run without starting its own`() {
        counter.press(PAD, 96)
        counter.press(PAD, 96)
        assertFalse(counter.press(PAD, 97))
        assertEquals(0, counter.count.value)
    }

    @Test fun `the press after a break starts a fresh run`() {
        counter.press(PAD, 96)
        counter.press(PAD, 97)
        assertEquals(0, counter.count.value)
        assertFalse(counter.press(PAD, 96))
        assertEquals(1, counter.count.value)
    }

    // The wizard's second trigger depends on this: a pad whose confirm is wrong is found by the
    // user pressing what they believe is confirm three times.
    @Test fun `three presses of a button that is not confirm still complete a run`() {
        assertFalse(counter.press(PAD, 97))
        assertFalse(counter.press(PAD, 97))
        assertTrue(counter.press(PAD, 97))
    }

    @Test fun `three presses of one button complete a run even after an earlier break`() {
        counter.press(PAD, 96)
        counter.press(PAD, 97)
        assertFalse(counter.press(PAD, 97))
        assertFalse(counter.press(PAD, 97))
        assertTrue(counter.press(PAD, 97))
    }

    @Test fun `the same button from another pad empties the run`() {
        assertFalse(counter.press(PAD, 96))
        assertFalse(counter.press(OTHER_PAD, 96))
        assertEquals(0, counter.count.value)
    }

    @Test fun `two pads alternating on one button never complete a run`() {
        repeat(4) {
            assertFalse(counter.press(PAD, 96))
            assertFalse(counter.press(OTHER_PAD, 96))
        }
        assertEquals(0, counter.count.value)
    }

    // What the decay does when the presses stop: the count and the pad and button it was counting
    // all go, so the next press begins a run rather than extending a stale one.
    @Test fun `reset clears the run and the pad and button it was counting`() {
        counter.press(PAD, 96)
        counter.press(PAD, 96)
        counter.reset()
        assertEquals(0, counter.count.value)
        assertFalse(counter.press(PAD, 96))
        assertEquals(1, counter.count.value)
    }

    @Test fun `a run that decayed part way through has to start over`() {
        counter.press(PAD, 96)
        counter.press(PAD, 96)
        counter.reset()
        assertFalse(counter.press(PAD, 96))
        assertFalse(counter.press(PAD, 96))
        assertTrue(counter.press(PAD, 96))
    }
}
