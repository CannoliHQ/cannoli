package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SELECT = 109
private const val START = 108
private const val A = 96
private val HOLD = ShortcutAction.OPEN_MENU_HOLD.holdMs.toLong()

class MenuShortcutGateTest {

    private val pass = MenuShortcutGate.Result.PASS
    private val consume = MenuShortcutGate.Result.CONSUME
    private val menu = MenuShortcutGate.Result.MENU
    private val replay = MenuShortcutGate.Result.REPLAY_PRESS

    private fun gate(vararg bound: Pair<ShortcutAction, Set<Int>>) =
        MenuShortcutGate().apply { setShortcuts(mapOf(*bound)) }

    private fun chordGate() = gate(ShortcutAction.OPEN_MENU to setOf(SELECT, START))

    @Test fun `nothing bound passes everything and holds nothing back`() {
        val g = gate()
        assertFalse(g.isBound)
        assertEquals(pass, g.onKeyDown(SELECT, 0))
        assertEquals(pass, g.onKeyDown(START, 0))
        assertTrue(g.takeReplays().isEmpty())
        assertNull(g.nextDeadline())
        assertEquals(pass, g.onKeyUp(SELECT))
        assertEquals(pass, g.onKeyUp(START))
    }

    @Test fun `the chord opens the menu and its first key never acts`() {
        val g = chordGate()
        assertEquals(consume, g.onKeyDown(SELECT, 0))
        assertEquals(menu, g.onKeyDown(START, 10))
        assertTrue(g.takeReplays().isEmpty())
        assertEquals(consume, g.onKeyDown(START, 20, isRepeat = true))
        assertEquals(consume, g.onKeyUp(SELECT))
        assertEquals(consume, g.onKeyUp(START))
        assertFalse(g.onTick(5000) == menu)
        assertTrue(g.takeReplays().isEmpty())
    }

    @Test fun `start first works the same way`() {
        val g = chordGate()
        assertEquals(consume, g.onKeyDown(START, 0))
        assertEquals(menu, g.onKeyDown(SELECT, 10))
        assertTrue(g.takeReplays().isEmpty())
    }

    @Test fun `another button replays the held key first`() {
        val g = chordGate()
        g.onKeyDown(SELECT, 0)
        assertEquals(pass, g.onKeyDown(A, 10))
        assertEquals(listOf(SELECT), g.takeReplays())
        assertEquals(pass, g.onKeyUp(A))
        assertEquals(pass, g.onKeyUp(SELECT))
    }

    @Test fun `a tap replays its press on release`() {
        val g = chordGate()
        g.onKeyDown(SELECT, 0)
        assertEquals(300L, g.nextDeadline())
        assertEquals(replay, g.onKeyUp(SELECT))
        assertTrue(g.takeReplays().isEmpty())
        assertNull(g.nextDeadline())
    }

    @Test fun `held past the wait it becomes an ordinary held key`() {
        val g = chordGate()
        g.onKeyDown(SELECT, 0)
        assertEquals(consume, g.onKeyDown(SELECT, 100, isRepeat = true))
        assertEquals(pass, g.onTick(299))
        assertTrue(g.takeReplays().isEmpty())
        assertEquals(pass, g.onTick(300))
        assertEquals(listOf(SELECT), g.takeReplays())
        assertEquals(pass, g.onKeyDown(SELECT, 400, isRepeat = true))
        assertEquals(pass, g.onKeyUp(SELECT))
    }

    // Select already reached the screen, so its release belongs to the screen too.
    @Test fun `a chord completed after the wait claims only the key that never acted`() {
        val g = chordGate()
        g.onKeyDown(SELECT, 0)
        g.onTick(300)
        g.takeReplays()
        assertEquals(menu, g.onKeyDown(START, 500))
        assertEquals(pass, g.onKeyUp(SELECT))
        assertEquals(consume, g.onKeyUp(START))
    }

    @Test fun `a short hold replays the press on release`() {
        val g = gate(ShortcutAction.OPEN_MENU_HOLD to setOf(START))
        assertEquals(consume, g.onKeyDown(START, 100))
        assertEquals(100 + HOLD, g.nextDeadline())
        assertEquals(pass, g.onTick(100 + HOLD - 1))
        assertEquals(replay, g.onKeyUp(START))
        assertNull(g.nextDeadline())
        assertEquals(pass, g.onTick(5000))
    }

    @Test fun `a full hold opens the menu once and its release is claimed`() {
        val g = gate(ShortcutAction.OPEN_MENU_HOLD to setOf(START))
        g.onKeyDown(START, 0)
        assertEquals(menu, g.onTick(HOLD))
        assertEquals(pass, g.onTick(HOLD * 2))
        assertEquals(consume, g.onKeyDown(START, HOLD + 500, isRepeat = true))
        assertEquals(consume, g.onKeyUp(START))
    }

    @Test fun `a hold still down from before is not counted`() {
        val g = gate(ShortcutAction.OPEN_MENU_HOLD to setOf(START))
        assertEquals(consume, g.onKeyDown(START, 0, isRepeat = true))
        assertNull(g.nextDeadline())
        assertEquals(pass, g.onKeyUp(START))
    }

    @Test fun `the chord beats a hold of its own start`() {
        val g = gate(
            ShortcutAction.OPEN_MENU to setOf(SELECT, START),
            ShortcutAction.OPEN_MENU_HOLD to setOf(START),
        )
        assertEquals(consume, g.onKeyDown(START, 0))
        assertEquals(HOLD, g.nextDeadline())
        assertEquals(menu, g.onKeyDown(SELECT, 10))
        assertEquals("the hold must not open it a second time", pass, g.onTick(5000))
        assertEquals(consume, g.onKeyUp(START))
        assertEquals(consume, g.onKeyUp(SELECT))
    }

    @Test fun `new bindings forget what the old ones were holding`() {
        val g = gate(ShortcutAction.OPEN_MENU_HOLD to setOf(START))
        g.onKeyDown(START, 0)
        g.setShortcuts(emptyMap())
        assertEquals(pass, g.onTick(5000))
        assertEquals(pass, g.onKeyUp(START))
    }
}
