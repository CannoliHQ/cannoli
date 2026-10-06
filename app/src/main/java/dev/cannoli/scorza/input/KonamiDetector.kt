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

internal fun konamiSequence(labelSet: ButtonLabelSet): List<CanonicalButton> = PREFIX + when (labelSet) {
    ButtonLabelSet.REDMOND -> listOf(CanonicalButton.BTN_EAST, CanonicalButton.BTN_SOUTH)
    ButtonLabelSet.PLUMBER, ButtonLabelSet.SHAPES, ButtonLabelSet.HEDGEHOG_6 -> listOf(CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST)
} + CanonicalButton.BTN_START

class KonamiDetector {

    private val progress = mutableMapOf<Int, Int>()

    fun reset() = progress.clear()

    fun onPress(port: Int, button: CanonicalButton, labelSet: ButtonLabelSet): Boolean {
        val sequence = konamiSequence(labelSet)
        val index = progress[port] ?: 0
        val next = if (button == sequence[index]) index + 1 else 0
        progress[port] = if (next == sequence.size) 0 else next
        return next == sequence.size
    }
}
