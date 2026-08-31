package dev.cannoli.igm

/**
 * Decides when a held set of keys means a shortcut.
 *
 * A chord fires only after being held together for [HOLD_MS]. Games use combinations too, and a
 * bare L+R firing the instant both are down would go off during normal play; a short hold separates
 * intent from coincidence without feeling slow. Binding uses a longer hold for the same reason.
 *
 * The keys still reach the game. A chord cannot be recognised until its last key arrives, by which
 * time the earlier ones have already been delivered, so consuming them would mean telling the core
 * a button was released that is still held. The artefact is accepted instead.
 *
 * Pure on purpose: this is the half of shortcuts worth testing, and it runs in the emulator process
 * where very little else can be.
 */
class ShortcutMatcher(
    private val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(),
) {

    /** What the caller should do about the keys held right now. */
    sealed interface Event {
        /** The chord has been held long enough. Fired once per press, not per tick. */
        data class Fired(val action: ShortcutAction) : Event
        /** A hold-style chord was released, for the actions that run only while held. */
        data class Released(val action: ShortcutAction) : Event
    }

    private val held = LinkedHashSet<Int>()
    private var armedAt = 0L
    private var armed: ShortcutAction? = null
    private var firing: ShortcutAction? = null

    /**
     * Feeds a key change and returns whatever it means, if anything.
     *
     * [now] is passed in rather than read so the hold can be tested without waiting for it.
     */
    fun onKey(keyCode: Int, down: Boolean, now: Long): Event? {
        val changed = if (down) held.add(keyCode) else held.remove(keyCode)
        if (!changed) return tick(now)

        // A chord that was firing stops the moment the set stops matching, which is what makes the
        // hold actions release cleanly rather than sticking on.
        val current = firing
        if (current != null && !matches(current)) {
            firing = null
            armed = null
            return Event.Released(current)
        }

        val candidate = bestMatch()
        if (candidate == null) {
            armed = null
            return null
        }
        if (candidate != armed) {
            armed = candidate
            armedAt = now
        }
        return tick(now)
    }

    /** Call on a timer as well as on key changes, or a chord held still would never reach its hold. */
    fun tick(now: Long): Event? {
        val candidate = armed ?: return null
        if (firing == candidate) return null
        if (now - armedAt < HOLD_MS) return null
        firing = candidate
        return Event.Fired(candidate)
    }

    /** Forgets everything, for a session ending or the chord table changing under it. */
    fun reset() {
        held.clear()
        armed = null
        firing = null
        armedAt = 0L
    }

    private fun matches(action: ShortcutAction): Boolean {
        val chord = shortcuts[action] ?: return false
        return chord.isNotEmpty() && held.containsAll(chord)
    }

    /**
     * The most specific chord currently satisfied.
     *
     * Longest wins, so binding L+R to one action and L+R+X to another does not make the second
     * unreachable: pressing all three satisfies both, and the one the user went further to press is
     * the one they meant.
     */
    private fun bestMatch(): ShortcutAction? = shortcuts.entries
        .filter { (_, chord) -> chord.isNotEmpty() && held.containsAll(chord) }
        .maxByOrNull { (_, chord) -> chord.size }
        ?.key

    companion object {
        /** Long enough to separate a shortcut from a game using the same buttons, short enough not to drag. */
        const val HOLD_MS = 300L
    }
}
