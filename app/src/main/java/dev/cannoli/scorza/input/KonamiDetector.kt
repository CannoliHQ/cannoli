package dev.cannoli.scorza.input

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.ui.ButtonLabelSet

private val PREFIX = listOf(
    CanonicalButton.BTN_UP,
    CanonicalButton.BTN_UP,
    CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT,
    CanonicalButton.BTN_RIGHT,
    CanonicalButton.BTN_LEFT,
    CanonicalButton.BTN_RIGHT,
)

/**
 * B then A land on the buttons this pad prints with those letters, so the physical pair flips with
 * the glyph style: a Nintendo-labelled pad puts B south and A east, a Redmond one the other way
 * round. SHAPES has no B or A to follow, so it takes the Nintendo motion, which is how PlayStation
 * ports of the code have always asked for it. Start closes the code, as Contra taught it.
 */
internal fun konamiSequence(labelSet: ButtonLabelSet): List<CanonicalButton> = PREFIX + when (labelSet) {
    ButtonLabelSet.REDMOND -> listOf(CanonicalButton.BTN_EAST, CanonicalButton.BTN_SOUTH)
    ButtonLabelSet.PLUMBER, ButtonLabelSet.SHAPES -> listOf(CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST)
} + CanonicalButton.BTN_START

/**
 * Progress is per port so a second pad jostling on the couch cannot break the run on the first.
 */
class KonamiDetector {

    private val progress = mutableMapOf<Int, Int>()

    fun reset() = progress.clear()

    /** True on the press that completes the code, which also arms the port for a fresh run. */
    fun onPress(port: Int, button: CanonicalButton, labelSet: ButtonLabelSet): Boolean {
        val sequence = konamiSequence(labelSet)
        val index = progress[port] ?: 0
        // Any wrong press ends the run outright, an up that could have opened a fresh one included.
        val next = if (button == sequence[index]) index + 1 else 0
        progress[port] = if (next == sequence.size) 0 else next
        return next == sequence.size
    }
}
