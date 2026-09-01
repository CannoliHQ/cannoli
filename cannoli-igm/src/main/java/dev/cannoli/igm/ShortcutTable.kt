package dev.cannoli.igm

/**
 * The wire form of the chord table, as native reads it.
 *
 * Flat `[action ordinal, hold ms, key count, keys...]`, repeated. One array rather than a call per
 * chord, so the table can never be read half written while a game is running.
 */
object ShortcutTable {

    /** What [ShortcutController] is told happened, matching the kinds native queues. */
    object Kind {
        const val FIRED = 0
        const val RELEASED = 1

        /** A hold-style chord is down and counting; the action has not run. */
        const val HOLD_ARMED = 2

        /** It was let go before the hold was up, so the action never ran. */
        const val HOLD_CANCELLED = 3
    }

    /** Matches RICOTTA_MAX_CHORD_KEYS. A longer chord is dropped rather than truncated: a chord
     *  missing a key would fire on fewer buttons than the user bound, which is worse than one that
     *  does not fire at all. */
    const val MAX_CHORD_KEYS = 8

    /** Matches RICOTTA_MAX_CHORDS. */
    const val MAX_CHORDS = 16

    /**
     * The override tier key for one action, one key per action rather than one for the table.
     *
     * Per action so a game can rebind the one chord that clashes and inherit the rest. A single
     * key holding the whole table would make changing one binding mean restating all of them,
     * which is what v1's source pointer did and the reason it is not what this does.
     */
    fun keyFor(action: ShortcutAction): String = "cannoli_shortcut_${action.name}"

    /** Keycodes as stored in a tier. Empty string means bound to nothing, which is not inherit. */
    fun parseChord(raw: String): Set<Int> =
        raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun formatChord(chord: Set<Int>): String = chord.joinToString(",")

    fun encode(table: Map<ShortcutAction, Set<Int>>): IntArray {
        val out = ArrayList<Int>()
        var chords = 0
        for ((action, chord) in table) {
            if (chord.isEmpty() || chord.size > MAX_CHORD_KEYS) continue
            if (chords == MAX_CHORDS) break
            out.add(action.ordinal)
            out.add(action.holdMs)
            out.add(chord.size)
            out.addAll(chord)
            chords++
        }
        return out.toIntArray()
    }
}
