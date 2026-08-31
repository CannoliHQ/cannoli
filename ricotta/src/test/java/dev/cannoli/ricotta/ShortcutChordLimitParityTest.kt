package dev.cannoli.ricotta

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.igm.ShortcutTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chords are matched in C, and Kotlin decides what to send it. The two ends have to agree on how
 * much fits: a table encoded past what native allocates is silently dropped on arrival, so a
 * shortcut the user bound simply never fires and nothing says why.
 */
class ShortcutChordLimitParityTest {

    private val source = File("jni/ricotta_bridge.c")

    private fun define(name: String): Int {
        val match = Regex("""#define\s+$name\s+(\d+)""").find(source.readText())
        return match?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("$name is not defined in ${source.name}")
    }

    @Test fun `the source is where the test thinks it is`() {
        assertTrue("expected ${source.absolutePath} to exist", source.exists())
    }

    @Test fun `the chord key limit matches native`() {
        assertEquals(define("RICOTTA_MAX_CHORD_KEYS"), ShortcutTable.MAX_CHORD_KEYS)
    }

    @Test fun `the chord count limit matches native`() {
        assertEquals(define("RICOTTA_MAX_CHORDS"), ShortcutTable.MAX_CHORDS)
    }

    // Native derives its key union from the same table, into a fixed array. Every action bound to a
    // chord of its own must still fit, or the last ones bound would forward no keys at all.
    @Test fun `every action could be bound at once and still fit`() {
        assertTrue(
            "there are ${ShortcutAction.entries.size} actions but room for ${ShortcutTable.MAX_CHORDS} chords",
            ShortcutAction.entries.size <= ShortcutTable.MAX_CHORDS,
        )
        assertTrue(
            "the key union has to hold one key per action at minimum",
            ShortcutAction.entries.size <= define("RICOTTA_MAX_SHORTCUT_KEYS"),
        )
    }
}
