package dev.cannoli.igm

/**
 * Turns chord presses into the things they were bound to.
 *
 * Native decides when a chord counts; this decides what it means. Every action here already exists
 * somewhere else in the menu or the bridge, so this is a dispatch table rather than an
 * implementation: a shortcut and a menu row that do the same thing should do it the same way.
 *
 * Matching is not here because it cannot be. A chord has to be recognised in the key event that
 * completes it, so the keys it claims can be taken back out of the input state before the core's
 * next poll; anything reaching this process is already several frames late, by which time the game
 * has acted on the press. [ShortcutTable.encode] is what native is given.
 */
class ShortcutController(
    private val controller: IGMController,
    /** Raises the menu. Supplied by the host, which owns the window this cannot reach. */
    private val showMenu: () -> Unit,
    /**
     * The keys that open the menu, when a chord also uses one of them.
     *
     * Such a key is swallowed whole before it reaches the core, so a chord built on it costs the
     * game nothing even before a match. Paying for that means the menu can no longer open on the
     * press: until the key comes back up there is no telling a menu press from a chord's modifier.
     *
     * Empty when no chord uses a menu key, in which case the menu opens natively on the press as it
     * always has and these events never arrive here at all.
     */
    private val menuKeys: Set<Int> = emptySet(),
) {

    /** Fires when an action wants the host to show something, since this owns no UI. */
    var onToast: ((ShortcutAction) -> Unit)? = null

    private var menuHeld = false
    private var menuClaimed = false

    /** A chord key moved. Only the menu key's own press is decided here. */
    fun onKey(keycode: Int, down: Boolean) {
        if (keycode !in menuKeys) return
        if (down) {
            menuHeld = true
            menuClaimed = false
            return
        }
        val claimed = menuClaimed
        menuHeld = false
        menuClaimed = false
        if (!claimed) showMenu()
    }

    /** Native matched a chord. [released] is the end of a hold-style action, not a new press. */
    fun onAction(ordinal: Int, released: Boolean) {
        val action = ShortcutAction.entries.getOrNull(ordinal) ?: return
        if (released) release(action) else fire(action)
    }

    fun reset() {
        menuHeld = false
        menuClaimed = false
    }

    private fun fire(action: ShortcutAction) {
        // A shortcut fired while the menu is up would act on a game the user has already stepped
        // away from, and the menu has its own bindings for the same things.
        if (controller.isOpen) return
        // This press was a chord's modifier, so releasing it must not also open the menu.
        if (menuHeld) menuClaimed = true
        when (action) {
            ShortcutAction.SAVE_STATE -> controller.saveState()
            ShortcutAction.LOAD_STATE -> controller.loadState()
            ShortcutAction.RESET_GAME -> controller.bridge.reset()
            ShortcutAction.SAVE_AND_QUIT, ShortcutAction.SAVE_AND_QUIT_HOLD -> controller.saveAndQuit()
            ShortcutAction.OPEN_MENU -> showMenu()
            // Menu first, guide second: openMenu clears the stack, so pushing the guide before
            // raising the window would throw it away. Stays down entirely when the game has no
            // guides, rather than opening a menu the user did not ask for.
            ShortcutAction.OPEN_GUIDE -> if (controller.hasGuides()) {
                showMenu()
                controller.openGuideFromShortcut()
            }
            ShortcutAction.TOGGLE_SHOW_FPS -> controller.bridge.toggleShowFps()
            ShortcutAction.CYCLE_EFFECT -> controller.bridge.toggleShader()
            ShortcutAction.TOGGLE_FF -> controller.bridge.toggleFastForward()
            ShortcutAction.HOLD_FF -> controller.bridge.setFastForwardHeld(true)
        }
        onToast?.invoke(action)
    }

    private fun release(action: ShortcutAction) {
        if (action == ShortcutAction.HOLD_FF) controller.bridge.setFastForwardHeld(false)
    }
}
