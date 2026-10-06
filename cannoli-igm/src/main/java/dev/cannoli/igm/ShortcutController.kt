package dev.cannoli.igm

class ShortcutController(
    private val controller: IGMController,
    private val showMenu: () -> Unit,
    private val menuKeys: Set<Int> = emptySet(),
) {

    var onToast: ((ShortcutAction) -> Unit)? = null

    private var menuHeld = false
    private var menuClaimed = false

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

    fun onAction(ordinal: Int, kind: Int) {
        val action = ShortcutAction.entries.getOrNull(ordinal) ?: return
        when (kind) {
            ShortcutTable.Kind.FIRED -> fire(action)
            ShortcutTable.Kind.RELEASED -> release(action)
            ShortcutTable.Kind.HOLD_ARMED -> if (!controller.isOpen) onHoldArmed?.invoke(action)
            ShortcutTable.Kind.HOLD_CANCELLED -> onHoldCancelled?.invoke(action)
        }
    }

    var onHoldArmed: ((ShortcutAction) -> Unit)? = null
    var onHoldCancelled: ((ShortcutAction) -> Unit)? = null

    var onRewindUnavailable: (() -> Unit)? = null

    fun reset() {
        menuHeld = false
        menuClaimed = false
    }

    private fun fire(action: ShortcutAction) {
        if (controller.isOpen) return
        if (menuHeld) menuClaimed = true
        when (action) {
            ShortcutAction.SAVE_STATE -> controller.saveState()
            ShortcutAction.LOAD_STATE -> controller.loadState()
            ShortcutAction.RESET_GAME -> controller.bridge.reset()
            ShortcutAction.SAVE_AND_QUIT, ShortcutAction.SAVE_AND_QUIT_HOLD -> controller.saveAndQuit()
            ShortcutAction.OPEN_MENU, ShortcutAction.OPEN_MENU_HOLD -> showMenu()
            ShortcutAction.OPEN_GUIDE -> if (controller.hasGuides()) {
                showMenu()
                controller.openGuideFromShortcut()
            }
            ShortcutAction.TOGGLE_SHOW_FPS -> controller.bridge.toggleShowFps()
            ShortcutAction.CYCLE_EFFECT -> controller.bridge.toggleShader()
            ShortcutAction.TOGGLE_FF -> controller.bridge.toggleFastForward()
            ShortcutAction.HOLD_FF -> controller.bridge.setFastForwardHeld(true)
            ShortcutAction.REWIND -> if (controller.bridge.rewindEnabled) {
                controller.bridge.setRewindHeld(true)
            } else {
                onRewindUnavailable?.invoke()
            }
        }
        onToast?.invoke(action)
    }

    private fun release(action: ShortcutAction) {
        when (action) {
            ShortcutAction.HOLD_FF -> controller.bridge.setFastForwardHeld(false)
            ShortcutAction.REWIND -> controller.bridge.setRewindHeld(false)
            else -> {}
        }
    }
}
