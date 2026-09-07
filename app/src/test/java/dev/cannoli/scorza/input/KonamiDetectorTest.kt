package dev.cannoli.scorza.input

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.ui.ButtonLabelSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KonamiDetectorTest {

    private val detector = KonamiDetector()

    private fun enter(
        buttons: List<CanonicalButton>,
        labelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
        port: Int = 0,
    ): Boolean = buttons.map { detector.onPress(port, it, labelSet) }.last()

    private fun code(labelSet: ButtonLabelSet) = konamiSequence(labelSet)

    @Test
    fun `the full code fires on the last press`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.dropLast(1).forEach {
            assertFalse(detector.onPress(0, it, ButtonLabelSet.PLUMBER))
        }
        assertTrue(detector.onPress(0, sequence.last(), ButtonLabelSet.PLUMBER))
    }

    @Test
    fun `plumber takes b then a as south then east`() {
        val ending = code(ButtonLabelSet.PLUMBER).dropLast(1).takeLast(2)
        assertTrue(ending == listOf(CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST))
    }

    @Test
    fun `redmond takes b then a as east then south`() {
        val ending = code(ButtonLabelSet.REDMOND).dropLast(1).takeLast(2)
        assertTrue(ending == listOf(CanonicalButton.BTN_EAST, CanonicalButton.BTN_SOUTH))
    }

    @Test
    fun `every label set closes on start`() {
        ButtonLabelSet.entries.forEach {
            assertTrue(code(it).last() == CanonicalButton.BTN_START)
        }
    }

    @Test
    fun `b and a alone do not fire it`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.dropLast(1).forEach {
            assertFalse(detector.onPress(0, it, ButtonLabelSet.PLUMBER))
        }
    }

    @Test
    fun `shapes takes the plumber motion`() {
        assertTrue(code(ButtonLabelSet.SHAPES) == code(ButtonLabelSet.PLUMBER))
    }

    @Test
    fun `the plumber ending does not open a redmond pad`() {
        assertFalse(enter(code(ButtonLabelSet.PLUMBER), labelSet = ButtonLabelSet.REDMOND))
    }

    @Test
    fun `a wrong press resets the run`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.dropLast(1).forEach { detector.onPress(0, it, ButtonLabelSet.PLUMBER) }
        detector.onPress(0, CanonicalButton.BTN_NORTH, ButtonLabelSet.PLUMBER)
        assertFalse(detector.onPress(0, sequence.last(), ButtonLabelSet.PLUMBER))
    }

    @Test
    fun `a wrong press invalidates the run even when it could have opened one`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.take(4).forEach { detector.onPress(0, it, ButtonLabelSet.PLUMBER) }
        detector.onPress(0, CanonicalButton.BTN_UP, ButtonLabelSet.PLUMBER)
        assertFalse(enter(sequence.drop(1)))
        assertTrue(enter(sequence))
    }

    @Test
    fun `ports keep their own progress`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.dropLast(1).forEach { detector.onPress(0, it, ButtonLabelSet.PLUMBER) }
        sequence.forEach { detector.onPress(1, CanonicalButton.BTN_NORTH, ButtonLabelSet.PLUMBER) }
        assertTrue(detector.onPress(0, sequence.last(), ButtonLabelSet.PLUMBER))
    }

    @Test
    fun `reset clears a run in progress`() {
        val sequence = code(ButtonLabelSet.PLUMBER)
        sequence.dropLast(1).forEach { detector.onPress(0, it, ButtonLabelSet.PLUMBER) }
        detector.reset()
        assertFalse(detector.onPress(0, sequence.last(), ButtonLabelSet.PLUMBER))
    }

    @Test
    fun `the code can be entered twice in a row`() {
        assertTrue(enter(code(ButtonLabelSet.PLUMBER)))
        assertTrue(enter(code(ButtonLabelSet.PLUMBER)))
    }
}
