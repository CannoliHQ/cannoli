package dev.cannoli.igm

/**
 * Turns a host's raw Android keycode into what the press means on this pad, using a Cannoli device
 * mapping.
 *
 * Null for a key this pad has no meaning for, which the screen handlers ignore. That used to be the
 * raw keycode passed through unchanged, so any unrecognised id could land on a handler branch that
 * happened to share its number.
 */
class IgmInputTranslator(private val mapping: IgmInputMapping?) {

    private val rawToCanonical: Map<Int, CanonicalButton> =
        mapping?.buttonKeycodes
            ?.flatMap { (button, codes) -> codes.map { it to button } }
            ?.toMap()
            ?: emptyMap()

    fun normalize(rawKeycode: Int): MenuAction? {
        val m = mapping
        val canonical = rawToCanonical[rawKeycode]
        if (m != null && canonical != null) {
            menuActionFor(canonical, m.menuConfirm, m.menuBack)?.let { return it }
        }
        // Reached only where the device's mapping said nothing about this key. A handheld whose
        // menu button reports KEYCODE_BACK has said something, and letting the fallback answer
        // first made menu and back the same button once you were inside the menu.
        return PASS_THROUGH[rawKeycode]
    }

    /**
     * Whether this raw keycode is the button that opens and closes the menu on this device.
     *
     * A mapping names BTN_MENU, so it answers for whatever button this pad calls menu. Without one
     * there is nothing to ask, and the platform's own menu keys are all that can be assumed.
     */
    fun isMenuKey(rawKeycode: Int): Boolean =
        if (mapping == null) rawKeycode in MENU_DEFAULTS else normalize(rawKeycode) == MenuAction.MENU

    companion object {
        private val MENU_DEFAULTS = setOf(4, 82, 110)

        // What a pad means with no profile behind it, and the reason this table is spelled out
        // rather than passing the keycode through: an id absent from here now means nothing at all,
        // where before it reached the handlers and could match a branch by sharing its number.
        private val PASS_THROUGH = mapOf(
            19 to MenuAction.UP, 20 to MenuAction.DOWN,
            21 to MenuAction.LEFT, 22 to MenuAction.RIGHT,
            96 to MenuAction.CONFIRM, 97 to MenuAction.BACK, 4 to MenuAction.BACK,
            99 to MenuAction.WEST, 100 to MenuAction.NORTH,
            102 to MenuAction.L1, 103 to MenuAction.R1,
            104 to MenuAction.L2, 105 to MenuAction.R2,
            106 to MenuAction.L3, 107 to MenuAction.R3,
            108 to MenuAction.START, 109 to MenuAction.SELECT,
            82 to MenuAction.MENU, 110 to MenuAction.MENU,
        )

    }
}
