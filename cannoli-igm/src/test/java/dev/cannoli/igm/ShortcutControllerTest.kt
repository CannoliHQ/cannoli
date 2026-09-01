package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val X = 99
private const val MENU = 82

/** A bridge that records the calls a shortcut is supposed to make. */
private class RecordingBridge : FakeRetroArchBridge() {
    var resets = 0
    var quits = 0
    var fpsToggles = 0
    var ffToggles = 0
    var shaderToggles = 0
    val ffHeld = mutableListOf<Boolean>()

    override fun reset() { resets++ }
    override fun quit() { quits++ }
    override fun toggleShowFps() { fpsToggles++ }
    override fun toggleFastForward() { ffToggles++ }
    override fun toggleShader() { shaderToggles++ }
    override fun setFastForwardHeld(held: Boolean) { ffHeld.add(held) }
}

/**
 * Chords are matched natively, so these drive the controller the way native does: by action, not by
 * key. Only the menu key still arrives here as a key, because only this side knows which keys open
 * the menu. [ShortcutTableTest] covers what native is handed.
 */
class ShortcutControllerTest {

    private var menusShown = 0

    private fun build(
        bridge: RecordingBridge,
        menuKeys: Set<Int> = emptySet(),
    ): Pair<ShortcutController, IGMController> {
        val igm = IGMController(
            bridge, "Game", SaveSlotStore(NO_SAVES),
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
        )
        val controller = ShortcutController(igm, showMenu = { menusShown++ }, menuKeys = menuKeys)
        return controller to igm
    }

    private fun ShortcutController.fire(action: ShortcutAction) =
        onAction(action.ordinal, ShortcutTable.Kind.FIRED)

    private fun ShortcutController.release(action: ShortcutAction) =
        onAction(action.ordinal, ShortcutTable.Kind.RELEASED)

    @Test fun `an action reaches the bridge call it names`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.RESET_GAME)
        assertEquals(1, bridge.resets)
    }

    @Test fun `save and quit archives before quitting`() {
        val bridge = RecordingBridge().apply { savesOnQuit = true }
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.SAVE_AND_QUIT)
        assertEquals(1, bridge.quits)
    }

    @Test fun `the fps and shader toggles reach the bridge`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.TOGGLE_SHOW_FPS)
        shortcuts.fire(ShortcutAction.CYCLE_EFFECT)
        assertEquals(1, bridge.fpsToggles)
        assertEquals(1, bridge.shaderToggles)
    }

    // The hold variant has to stop when the chord breaks, or fast forward sticks on after release.
    @Test fun `held fast forward starts and stops with the chord`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.HOLD_FF)
        assertEquals(listOf(true), bridge.ffHeld)
        shortcuts.release(ShortcutAction.HOLD_FF)
        assertEquals(listOf(true, false), bridge.ffHeld)
    }

    @Test fun `the latching fast forward does not report a hold`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.TOGGLE_FF)
        shortcuts.release(ShortcutAction.TOGGLE_FF)
        assertEquals(1, bridge.ffToggles)
        assertTrue("a latch has nothing to release", bridge.ffHeld.isEmpty())
    }

    // The menu has its own bindings for these, and a shortcut firing behind it would act on a game
    // the user has already stepped away from.
    @Test fun `nothing fires while the menu is open`() {
        val bridge = RecordingBridge()
        val (shortcuts, igm) = build(bridge)
        igm.openMenu()
        shortcuts.fire(ShortcutAction.RESET_GAME)
        assertEquals(0, bridge.resets)
    }

    @Test fun `open menu asks the host rather than the controller`() {
        val bridge = RecordingBridge()
        val (shortcuts, igm) = build(bridge)
        shortcuts.fire(ShortcutAction.OPEN_MENU)
        assertEquals(1, menusShown)
        assertFalse("the host raises the window, not this", igm.isOpen)
    }

    // A game with no guides should not get a menu it never asked for.
    @Test fun `the guide shortcut stays quiet when there is nothing to open`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.OPEN_GUIDE)
        assertEquals(0, menusShown)
    }

    @Test fun `an action native does not know is ignored`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.onAction(ShortcutAction.entries.size, ShortcutTable.Kind.FIRED)
        shortcuts.onAction(-1, ShortcutTable.Kind.FIRED)
        assertEquals(0, bridge.resets)
        assertEquals(0, bridge.quits)
    }

    // The bug this fixes: with the hold gone, the (Hold) variant fired instantly and was a
    // duplicate of the plain action, despite its label promising a long press.
    @Test fun `an armed hold does not quit until it fires`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        val armed = mutableListOf<ShortcutAction>()
        shortcuts.onHoldArmed = { armed.add(it) }

        shortcuts.onAction(ShortcutAction.SAVE_AND_QUIT_HOLD.ordinal, ShortcutTable.Kind.HOLD_ARMED)
        assertEquals("arming only prompts", 0, bridge.quits)
        assertEquals(listOf(ShortcutAction.SAVE_AND_QUIT_HOLD), armed)

        shortcuts.fire(ShortcutAction.SAVE_AND_QUIT_HOLD)
        assertEquals(1, bridge.quits)
    }

    @Test fun `letting go before the hold is up quits nothing`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        var cancels = 0
        shortcuts.onHoldCancelled = { cancels++ }

        shortcuts.onAction(ShortcutAction.SAVE_AND_QUIT_HOLD.ordinal, ShortcutTable.Kind.HOLD_ARMED)
        shortcuts.onAction(ShortcutAction.SAVE_AND_QUIT_HOLD.ordinal, ShortcutTable.Kind.HOLD_CANCELLED)
        assertEquals(0, bridge.quits)
        assertEquals("the prompt has to come down again", 1, cancels)
    }

    // The prompt tells the user to keep holding, which means nothing behind an open menu.
    @Test fun `no hold prompt while the menu is open`() {
        val bridge = RecordingBridge()
        val (shortcuts, igm) = build(bridge)
        var prompts = 0
        shortcuts.onHoldArmed = { prompts++ }
        igm.openMenu()

        shortcuts.onAction(ShortcutAction.SAVE_AND_QUIT_HOLD.ordinal, ShortcutTable.Kind.HOLD_ARMED)
        assertEquals(0, prompts)
    }

    // Native only hands the menu key over when a chord uses it, so opening it is this side's job
    // from then on. It waits for the release, since until then it could still be a modifier.
    @Test fun `the menu key alone still opens the menu, on release`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge, menuKeys = setOf(MENU))
        shortcuts.onKey(MENU, down = true)
        assertEquals("nothing yet: this could still become a chord", 0, menusShown)
        shortcuts.onKey(MENU, down = false)
        assertEquals(1, menusShown)
    }

    @Test fun `a chord on the menu key does not also open the menu`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge, menuKeys = setOf(MENU))
        shortcuts.onKey(MENU, down = true)
        shortcuts.onKey(X, down = true)
        shortcuts.fire(ShortcutAction.RESET_GAME)
        shortcuts.onKey(X, down = false)
        shortcuts.onKey(MENU, down = false)
        assertEquals("that press was a modifier", 0, menusShown)
    }

    // Otherwise one chord would poison the menu key for the rest of the session.
    @Test fun `the menu opens again on the press after a chord`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge, menuKeys = setOf(MENU))
        shortcuts.onKey(MENU, down = true)
        shortcuts.fire(ShortcutAction.RESET_GAME)
        shortcuts.onKey(MENU, down = false)
        shortcuts.onKey(MENU, down = true)
        shortcuts.onKey(MENU, down = false)
        assertEquals(1, menusShown)
    }
}
