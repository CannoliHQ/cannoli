package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val L = 102
private const val R = 103

/**
 * The chord table is matched in C now, so this covers the one part still testable here: that what
 * native is handed says what the bindings said.
 */
class ShortcutTableTest {

    /** Reads the wire form back, so a test states the chords rather than the offsets. */
    private fun decode(flat: IntArray): Map<Int, List<Int>> {
        val out = LinkedHashMap<Int, List<Int>>()
        var at = 0
        while (at + 1 < flat.size) {
            val action = flat[at++]
            val count = flat[at++]
            out[action] = (0 until count).map { flat[at + it] }
            at += count
        }
        return out
    }

    @Test fun `a chord encodes as its action, its length and its keys`() {
        val flat = ShortcutTable.encode(mapOf(ShortcutAction.RESET_GAME to setOf(L, R)))
        assertEquals(intArrayOf(ShortcutAction.RESET_GAME.ordinal, 2, L, R).toList(), flat.toList())
    }

    @Test fun `every bound chord survives the round trip`() {
        val table = mapOf(
            ShortcutAction.SAVE_STATE to setOf(L),
            ShortcutAction.RESET_GAME to setOf(L, R),
        )
        val decoded = decode(ShortcutTable.encode(table))
        assertEquals(2, decoded.size)
        assertEquals(listOf(L), decoded[ShortcutAction.SAVE_STATE.ordinal])
        assertEquals(listOf(L, R), decoded[ShortcutAction.RESET_GAME.ordinal])
    }

    @Test fun `an unbound action is left out entirely`() {
        val flat = ShortcutTable.encode(mapOf(ShortcutAction.SAVE_STATE to emptySet()))
        assertTrue("an empty chord would match on nothing held", flat.isEmpty())
    }

    // Dropped rather than truncated: a chord short of a key fires on fewer buttons than the user
    // bound, which is worse than one that never fires.
    @Test fun `a chord longer than native can hold is dropped, not cut short`() {
        val long = (1..ShortcutTable.MAX_CHORD_KEYS + 1).toSet()
        val flat = ShortcutTable.encode(
            mapOf(ShortcutAction.SAVE_STATE to long, ShortcutAction.RESET_GAME to setOf(L, R)),
        )
        val decoded = decode(flat)
        assertEquals(setOf(ShortcutAction.RESET_GAME.ordinal), decoded.keys)
    }

    @Test fun `no more chords are sent than native has room for`() {
        val table = ShortcutAction.entries.associateWith { setOf(it.ordinal + 200) }
        val decoded = decode(ShortcutTable.encode(table))
        assertTrue(decoded.size <= ShortcutTable.MAX_CHORDS)
    }

    @Test fun `nothing bound encodes to nothing`() {
        assertTrue(ShortcutTable.encode(emptyMap()).isEmpty())
    }
}
