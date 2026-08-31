package dev.cannoli.igm

/**
 * The wire form of the chord table, as native reads it.
 *
 * Flat `[action ordinal, key count, keys...]` triples, repeated. One array rather than a call per
 * chord, so the table can never be read half written while a game is running.
 */
object ShortcutTable {

    /** Matches RICOTTA_MAX_CHORD_KEYS. A longer chord is dropped rather than truncated: a chord
     *  missing a key would fire on fewer buttons than the user bound, which is worse than one that
     *  does not fire at all. */
    const val MAX_CHORD_KEYS = 8

    /** Matches RICOTTA_MAX_CHORDS. */
    const val MAX_CHORDS = 16

    fun encode(table: Map<ShortcutAction, Set<Int>>): IntArray {
        val out = ArrayList<Int>()
        var chords = 0
        for ((action, chord) in table) {
            if (chord.isEmpty() || chord.size > MAX_CHORD_KEYS) continue
            if (chords == MAX_CHORDS) break
            out.add(action.ordinal)
            out.add(chord.size)
            out.addAll(chord)
            chords++
        }
        return out.toIntArray()
    }
}
