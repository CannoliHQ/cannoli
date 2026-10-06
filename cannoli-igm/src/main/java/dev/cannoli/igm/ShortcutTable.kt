package dev.cannoli.igm

object ShortcutTable {

    object Kind {
        const val FIRED = 0
        const val RELEASED = 1

        const val HOLD_ARMED = 2

        const val HOLD_CANCELLED = 3
    }

    const val MAX_CHORD_KEYS = 8

    const val MAX_CHORDS = 16

    fun keyFor(action: ShortcutAction): String = "cannoli_shortcut_${action.name}"

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
            out.add(if (action.passThrough) 1 else 0)
            out.add(chord.size)
            out.addAll(chord)
            chords++
        }
        return out.toIntArray()
    }
}
