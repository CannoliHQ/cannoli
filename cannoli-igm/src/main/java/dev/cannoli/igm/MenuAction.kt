package dev.cannoli.igm

enum class MenuAction {
    UP, DOWN, LEFT, RIGHT,
    CONFIRM, BACK,
    NORTH, WEST,
    L1, R1, L2, R2, L3, R3,
    START, SELECT, MENU,
}

fun menuActionFor(
    button: CanonicalButton,
    menuConfirm: CanonicalButton,
    menuBack: CanonicalButton,
): MenuAction? {
    if (button == menuConfirm) return MenuAction.CONFIRM
    if (button == menuBack) return MenuAction.BACK
    return when (button) {
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
        CanonicalButton.BTN_LSTICK_X,
        CanonicalButton.BTN_LSTICK_Y,
        CanonicalButton.BTN_RSTICK_X,
        CanonicalButton.BTN_RSTICK_Y -> null
    }
}

