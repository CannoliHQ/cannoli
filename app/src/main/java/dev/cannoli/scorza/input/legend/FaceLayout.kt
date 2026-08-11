package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton

enum class FaceLayout {
    STANDARD,
    NINTENDO;

    fun standardFaceBindings(): Map<CanonicalButton, Int> = when (this) {
        STANDARD -> mapOf(
            CanonicalButton.BTN_SOUTH to 96,
            CanonicalButton.BTN_EAST to 97,
            CanonicalButton.BTN_WEST to 99,
            CanonicalButton.BTN_NORTH to 100,
        )
        NINTENDO -> mapOf(
            CanonicalButton.BTN_SOUTH to 97,
            CanonicalButton.BTN_EAST to 96,
            CanonicalButton.BTN_NORTH to 99,
            CanonicalButton.BTN_WEST to 100,
        )
    }

    val confirmButton: CanonicalButton
        get() = if (this == NINTENDO) CanonicalButton.BTN_EAST else CanonicalButton.BTN_SOUTH
}
