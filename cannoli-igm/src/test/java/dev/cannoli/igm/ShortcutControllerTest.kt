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

    var rewindOn = true
    val rewindHeld = mutableListOf<Boolean>()
    override val rewindEnabled: Boolean get() = rewindOn
    override fun setRewindHeld(held: Boolean) { rewindHeld.add(held) }
}

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

    @Test fun `the held menu opens the menu the same way`() {
        val bridge = RecordingBridge()
        val (shortcuts, igm) = build(bridge)
        shortcuts.onAction(ShortcutAction.OPEN_MENU_HOLD.ordinal, ShortcutTable.Kind.HOLD_ARMED)
        assertEquals("arming is not firing", 0, menusShown)
        shortcuts.onAction(ShortcutAction.OPEN_MENU_HOLD.ordinal, ShortcutTable.Kind.HOLD_CANCELLED)
        assertEquals(0, menusShown)
        shortcuts.fire(ShortcutAction.OPEN_MENU_HOLD)
        assertEquals(1, menusShown)
        assertFalse(igm.isOpen)
    }

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

    @Test fun `no hold prompt while the menu is open`() {
        val bridge = RecordingBridge()
        val (shortcuts, igm) = build(bridge)
        var prompts = 0
        shortcuts.onHoldArmed = { prompts++ }
        igm.openMenu()

        shortcuts.onAction(ShortcutAction.SAVE_AND_QUIT_HOLD.ordinal, ShortcutTable.Kind.HOLD_ARMED)
        assertEquals(0, prompts)
    }

    @Test fun `rewind runs while the chord is held`() {
        val bridge = RecordingBridge()
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.REWIND)
        assertEquals(listOf(true), bridge.rewindHeld)
        shortcuts.release(ShortcutAction.REWIND)
        assertEquals(listOf(true, false), bridge.rewindHeld)
    }

    @Test fun `rewind reports itself unavailable when the buffer is off`() {
        val bridge = RecordingBridge().apply { rewindOn = false }
        val (shortcuts, _) = build(bridge)
        var refusals = 0
        shortcuts.onRewindUnavailable = { refusals++ }

        shortcuts.fire(ShortcutAction.REWIND)
        assertEquals(1, refusals)
        assertTrue("nothing should be asked of the bridge", bridge.rewindHeld.isEmpty())
    }

    @Test fun `a refused rewind still releases`() {
        val bridge = RecordingBridge().apply { rewindOn = false }
        val (shortcuts, _) = build(bridge)
        shortcuts.fire(ShortcutAction.REWIND)
        shortcuts.release(ShortcutAction.REWIND)
        assertEquals(listOf(false), bridge.rewindHeld)
    }

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
