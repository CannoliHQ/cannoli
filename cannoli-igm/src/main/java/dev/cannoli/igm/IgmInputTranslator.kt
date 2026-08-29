package dev.cannoli.igm

/**
 * Translates a host's raw Android keycode to the standard keycode the IGMController's
 * per-screen handlers expect, using a Cannoli device mapping. When no mapping is supplied
 * (or a keycode is unmapped), the raw keycode passes through unchanged (identity), which
 * preserves behavior for non-Cannoli launches and for the dpad/system keys.
 */
class IgmInputTranslator(private val mapping: IgmInputMapping?) {

    private val rawToCanonical: Map<Int, CanonicalButton> =
        mapping?.buttonKeycodes
            ?.flatMap { (button, codes) -> codes.map { it to button } }
            ?.toMap()
            ?: emptyMap()

    /** Raw Android keycode -> normalized IGM keycode (19/20/21/22/96/97/99/100/102/103). */
    fun normalize(rawKeycode: Int): Int {
        val m = mapping
        val canonical = rawToCanonical[rawKeycode]
        if (m != null && canonical != null) {
            // Confirm and back are whichever face buttons this device calls them, so the device's
            // own answer wins over the button's position on the pad.
            if (canonical == m.menuConfirm) return CONFIRM
            if (canonical == m.menuBack) return BACK
            normalized(canonical)?.let { return it }
        }
        // Reached only where the device's mapping said nothing about this key. A handheld whose
        // menu button reports KEYCODE_BACK has said something, and letting the fallback answer
        // first made menu and back the same button once you were inside the menu.
        PASS_THROUGH[rawKeycode]?.let { return it }
        return rawKeycode
    }

    companion object {
        private const val CONFIRM = 96
        private const val BACK = 97

        private val PASS_THROUGH = mapOf(19 to 19, 20 to 20, 21 to 21, 22 to 22, 4 to BACK)

        /**
         * What the IGM hears for each button the device mapping names, as Android's own codes.
         *
         * Exhaustive with no else, so adding a CanonicalButton stops the build rather than falling
         * through to a raw keycode that only a conventionally numbered pad would get right.
         */
        private fun normalized(button: CanonicalButton): Int? = when (button) {
            CanonicalButton.BTN_UP -> 19
            CanonicalButton.BTN_DOWN -> 20
            CanonicalButton.BTN_LEFT -> 21
            CanonicalButton.BTN_RIGHT -> 22
            // Reached only when a mapping names neither as confirm or back, which it always does.
            CanonicalButton.BTN_SOUTH -> CONFIRM
            CanonicalButton.BTN_EAST -> BACK
            CanonicalButton.BTN_WEST -> 99
            CanonicalButton.BTN_NORTH -> 100
            CanonicalButton.BTN_L -> 102
            CanonicalButton.BTN_R -> 103
            CanonicalButton.BTN_L2 -> 104
            CanonicalButton.BTN_R2 -> 105
            CanonicalButton.BTN_L3 -> 106
            CanonicalButton.BTN_R3 -> 107
            CanonicalButton.BTN_START -> 108
            CanonicalButton.BTN_SELECT -> 109
            CanonicalButton.BTN_MENU -> 82
            // Axes rather than buttons, so they never appear in a keycode map. Null rather than a
            // keycode, because inventing one would give a stick a button's meaning.
            CanonicalButton.BTN_LSTICK_X,
            CanonicalButton.BTN_LSTICK_Y,
            CanonicalButton.BTN_RSTICK_X,
            CanonicalButton.BTN_RSTICK_Y -> null
        }
    }
}
