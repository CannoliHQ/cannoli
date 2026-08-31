package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val L = 102
private const val R = 103
private const val X = 99
private const val START = 108

class ShortcutMatcherTest {

    private fun matcher(vararg pairs: Pair<ShortcutAction, Set<Int>>) =
        ShortcutMatcher(pairs.toMap())

    @Test fun `a complete chord does not fire until it has been held`() {
        val m = matcher(ShortcutAction.SAVE_STATE to setOf(L, R))
        assertNull(m.onKey(L, down = true, now = 0))
        assertNull("both down is not yet a shortcut", m.onKey(R, down = true, now = 0))
        assertNull(m.tick(ShortcutMatcher.HOLD_MS - 1))
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.SAVE_STATE),
            m.tick(ShortcutMatcher.HOLD_MS),
        )
    }

    // The reason for the hold: a game using the same two buttons must not trigger a shortcut.
    @Test fun `a chord released before the hold fires nothing`() {
        val m = matcher(ShortcutAction.SAVE_STATE to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        assertNull(m.onKey(R, down = false, now = 100))
        assertNull("releasing disarms it", m.tick(1_000))
    }

    @Test fun `holding on does not fire again`() {
        val m = matcher(ShortcutAction.SAVE_STATE to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.SAVE_STATE),
            m.tick(ShortcutMatcher.HOLD_MS),
        )
        assertNull("one press is one firing", m.tick(ShortcutMatcher.HOLD_MS + 5_000))
    }

    @Test fun `releasing a fired chord reports it, for the hold actions`() {
        val m = matcher(ShortcutAction.HOLD_FF to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        m.tick(ShortcutMatcher.HOLD_MS)
        assertEquals(
            ShortcutMatcher.Event.Released(ShortcutAction.HOLD_FF),
            m.onKey(R, down = false, now = 500),
        )
    }

    @Test fun `a chord fires again after being released and pressed once more`() {
        val m = matcher(ShortcutAction.SAVE_STATE to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        m.tick(ShortcutMatcher.HOLD_MS)
        m.onKey(R, down = false, now = 400)
        m.onKey(R, down = true, now = 500)
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.SAVE_STATE),
            m.tick(500 + ShortcutMatcher.HOLD_MS),
        )
    }

    // Otherwise binding L+R and L+R+X would make the longer one unreachable, since pressing all
    // three satisfies both and the shorter one would win on arrival order.
    @Test fun `the longest satisfied chord wins`() {
        val m = matcher(
            ShortcutAction.SAVE_STATE to setOf(L, R),
            ShortcutAction.LOAD_STATE to setOf(L, R, X),
        )
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        m.onKey(X, down = true, now = 10)
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.LOAD_STATE),
            m.tick(10 + ShortcutMatcher.HOLD_MS),
        )
    }

    @Test fun `extra keys held alongside a chord do not stop it`() {
        val m = matcher(ShortcutAction.OPEN_MENU to setOf(L, R))
        m.onKey(START, down = true, now = 0)
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.OPEN_MENU),
            m.tick(ShortcutMatcher.HOLD_MS),
        )
    }

    @Test fun `an unbound action never fires`() {
        val m = matcher(ShortcutAction.SAVE_STATE to emptySet())
        m.onKey(L, down = true, now = 0)
        assertNull(m.tick(10_000))
    }

    @Test fun `nothing bound means nothing fires`() {
        val m = ShortcutMatcher()
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        assertNull(m.tick(10_000))
    }

    @Test fun `reset forgets a chord that was firing`() {
        val m = matcher(ShortcutAction.HOLD_FF to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        m.tick(ShortcutMatcher.HOLD_MS)
        m.reset()
        assertNull("nothing is held any more", m.onKey(R, down = false, now = 500))
    }

    // Key repeat delivers the same keycode down many times; the hold must be measured from the
    // first, or a repeating key would keep pushing the deadline away and never fire.
    @Test fun `a repeated key down does not restart the hold`() {
        val m = matcher(ShortcutAction.SAVE_STATE to setOf(L, R))
        m.onKey(L, down = true, now = 0)
        m.onKey(R, down = true, now = 0)
        m.onKey(R, down = true, now = 250)
        assertEquals(
            ShortcutMatcher.Event.Fired(ShortcutAction.SAVE_STATE),
            m.onKey(R, down = true, now = ShortcutMatcher.HOLD_MS),
        )
    }
}
