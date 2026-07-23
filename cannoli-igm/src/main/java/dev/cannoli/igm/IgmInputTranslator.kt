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
        PASS_THROUGH[rawKeycode]?.let { return it }
        val m = mapping ?: return rawKeycode
        val canonical = rawToCanonical[rawKeycode] ?: return rawKeycode
        return when (canonical) {
            CanonicalButton.BTN_UP -> 19
            CanonicalButton.BTN_DOWN -> 20
            CanonicalButton.BTN_LEFT -> 21
            CanonicalButton.BTN_RIGHT -> 22
            m.menuConfirm -> 96
            m.menuBack -> 97
            CanonicalButton.BTN_WEST -> 99
            CanonicalButton.BTN_NORTH -> 100
            CanonicalButton.BTN_L -> 102
            CanonicalButton.BTN_R -> 103
            else -> rawKeycode
        }
    }

    companion object {
        private val PASS_THROUGH = mapOf(19 to 19, 20 to 20, 21 to 21, 22 to 22, 4 to 97)
    }
}
