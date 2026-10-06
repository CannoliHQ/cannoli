package dev.cannoli.igm

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
        return PASS_THROUGH[rawKeycode]
    }

    fun isMenuKey(rawKeycode: Int): Boolean =
        if (mapping == null) rawKeycode in MENU_DEFAULTS else normalize(rawKeycode) == MenuAction.MENU

    fun canonicalFor(rawKeycode: Int): CanonicalButton? {
        rawToCanonical[rawKeycode]?.let { return it }
        return if (mapping != null) HAT_AND_AXIS_FALLBACK[rawKeycode] else null
    }

    companion object {
        private val MENU_DEFAULTS = setOf(4, 82, 110)

        private val HAT_AND_AXIS_FALLBACK = mapOf(
            19 to CanonicalButton.BTN_UP, 20 to CanonicalButton.BTN_DOWN,
            21 to CanonicalButton.BTN_LEFT, 22 to CanonicalButton.BTN_RIGHT,
            104 to CanonicalButton.BTN_L2, 105 to CanonicalButton.BTN_R2,
        )

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
