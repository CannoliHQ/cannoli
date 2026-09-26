package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

private const val SELECT = 109
private const val START = 108
private const val SOUTH = 96
private val HOLD = ShortcutAction.OPEN_MENU_HOLD.holdMs.toLong()

/**
 * With the menu up the keys go to the menu's window, not to native, so the menu shortcuts have to
 * be matched here for the menu's own screens to see them.
 */
class IGMMenuShortcutTest {

    private val mapping = IgmInputMapping(
        buttonKeycodes = mapOf(
            CanonicalButton.BTN_SELECT to listOf(SELECT),
            CanonicalButton.BTN_START to listOf(START),
            CanonicalButton.BTN_SOUTH to listOf(SOUTH),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
    )

    private var now = 0L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private var closes = 0

    private fun controller(shortcuts: Map<ShortcutAction, Set<Int>>) =
        testController(FakeRetroArchBridge()).apply {
            setInputMapping(mapping)
            setMenuShortcuts(shortcuts)
            clock = { now }
            scheduleMenuHold = { delay, block -> pending += (now + delay) to block }
            onClose = { closes++ }
            openMenu()
        }

    private fun advanceTo(t: Long) {
        now = t
        val due = pending.filter { it.first <= t }
        pending.removeAll(due)
        due.forEach { it.second() }
    }

    @Test fun `select and start reach the menu as the menu key, once`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START)))
        c.handleKeyDown(SELECT)
        c.handleKeyDown(START)
        assertEquals(1, closes)
        c.handleKeyDown(START, isRepeat = true)
        c.handleKeyUp(SELECT)
        c.handleKeyUp(START)
        assertEquals(1, closes)
    }

    @Test fun `holding start reaches the menu as the menu key, once`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(START)))
        c.handleKeyDown(START)
        assertEquals(0, closes)
        c.handleKeyDown(START, isRepeat = true)
        advanceTo(HOLD - 1)
        assertEquals(0, closes)
        advanceTo(HOLD)
        assertEquals(1, closes)
        c.handleKeyUp(START)
        assertEquals(1, closes)
    }

    @Test fun `a short start press is still a start press`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(START)))
        c.handleKeyDown(START)
        advanceTo(300)
        c.handleKeyUp(START)
        advanceTo(5000)
        assertEquals(0, closes)
    }

    // The hold that opened the menu from the game is still down when the menu arrives, and only
    // its repeats reach this side. They must not start a second count that closes it again.
    @Test fun `a hold carried over from the game does not close the menu`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(START)))
        c.handleKeyDown(START, isRepeat = true)
        advanceTo(5000)
        c.handleKeyUp(START)
        assertEquals(0, closes)
    }

    @Test fun `select held back still lets another button act`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START)))
        c.handleKeyDown(SELECT)
        c.handleKeyDown(SOUTH)
        assertEquals("confirm on Resume still resumes", 1, closes)
    }

    @Test fun `select tapped or held alone never opens anything`() {
        val c = controller(mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START)))
        c.handleKeyDown(SELECT)
        c.handleKeyUp(SELECT)
        c.handleKeyDown(SELECT)
        advanceTo(MenuShortcutGate.CHORD_WAIT_MS)
        c.handleKeyDown(SELECT, isRepeat = true)
        c.handleKeyUp(SELECT)
        assertEquals(0, closes)
    }
}
