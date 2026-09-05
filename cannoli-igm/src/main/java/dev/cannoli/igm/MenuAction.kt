package dev.cannoli.igm

/**
 * What a press means once the device's own profile has been consulted, as opposed to which button
 * was pressed.
 *
 * The distinction matters because the profile decides which physical button confirms: on a Nintendo
 * layout that is the right-hand one, elsewhere the bottom one. So a screen handler cannot be given
 * [CanonicalButton], which is a position, without every handler having to consult the profile for
 * itself. It is given the resolved answer instead.
 *
 * Shared by both processes on purpose. The launcher and the in-game menu run separately and cannot
 * share a dispatcher, but they resolve the same profile, and this is the vocabulary they resolve it
 * into so the two cannot drift on what confirm means.
 */
enum class MenuAction {
    UP, DOWN, LEFT, RIGHT,
    CONFIRM, BACK,
    NORTH, WEST,
    L1, R1, L2, R2, L3, R3,
    START, SELECT, MENU,
}

/**
 * What [button] means on a pad whose profile names [menuConfirm] and [menuBack].
 *
 * The single place that question is answered. Both processes resolve the same profile, and when
 * each did its own resolving they could disagree about which face button confirms while both
 * believing they had read the profile correctly.
 */
fun menuActionFor(
    button: CanonicalButton,
    menuConfirm: CanonicalButton,
    menuBack: CanonicalButton,
): MenuAction? {
    // The profile's own answer wins over where the button sits on the pad.
    if (button == menuConfirm) return MenuAction.CONFIRM
    if (button == menuBack) return MenuAction.BACK
    return when (button) {
        // Reached only when a profile names neither as confirm or back, which it always does.
        CanonicalButton.BTN_SOUTH -> MenuAction.CONFIRM
        CanonicalButton.BTN_EAST -> MenuAction.BACK
        CanonicalButton.BTN_UP -> MenuAction.UP
        CanonicalButton.BTN_DOWN -> MenuAction.DOWN
        CanonicalButton.BTN_LEFT -> MenuAction.LEFT
        CanonicalButton.BTN_RIGHT -> MenuAction.RIGHT
        CanonicalButton.BTN_WEST -> MenuAction.WEST
        CanonicalButton.BTN_NORTH -> MenuAction.NORTH
        CanonicalButton.BTN_L -> MenuAction.L1
        CanonicalButton.BTN_R -> MenuAction.R1
        CanonicalButton.BTN_L2 -> MenuAction.L2
        CanonicalButton.BTN_R2 -> MenuAction.R2
        CanonicalButton.BTN_L3 -> MenuAction.L3
        CanonicalButton.BTN_R3 -> MenuAction.R3
        CanonicalButton.BTN_START -> MenuAction.START
        CanonicalButton.BTN_SELECT -> MenuAction.SELECT
        CanonicalButton.BTN_MENU -> MenuAction.MENU
        // Axes rather than buttons. Null rather than an action, because inventing one would give a
        // stick a button's meaning.
        CanonicalButton.BTN_LSTICK_X,
        CanonicalButton.BTN_LSTICK_Y,
        CanonicalButton.BTN_RSTICK_X,
        CanonicalButton.BTN_RSTICK_Y -> null
    }
}

