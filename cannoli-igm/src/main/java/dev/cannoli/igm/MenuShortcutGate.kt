package dev.cannoli.igm

/**
 * The menu shortcuts outside the game, for a pad with no menu button: [ShortcutAction.OPEN_MENU]
 * as a chord and [ShortcutAction.OPEN_MENU_HOLD] as a hold, read off raw keycodes the way native
 * reads them in the game.
 *
 * Shared by the launcher's dispatcher and the in-game menu, which each ask it about every key
 * before giving the key its own meaning, and act on the answer. After every call the host takes
 * [takeReplays] first and presses those keys, in order, before acting on the result. It keeps no
 * clock of its own: the host passes the time in and calls [onTick] at [nextDeadline].
 */
class MenuShortcutGate {

    enum class Result {
        /** Not a shortcut key, or not yet: the key means what it always does. */
        PASS,

        /** The key's own action must not run now; its release is decided later. */
        CONSUME,

        /** Open the menu, in place of this key's own action. */
        MENU,

        /**
         * A key held back was let go before anything claimed it, so it was an ordinary press after
         * all: run the press it was holding back, then this release as usual.
         */
        REPLAY_PRESS,
    }

    private var chord: Set<Int> = emptySet()
    private var hold: Set<Int> = emptySet()
    private var holdMs: Long = ShortcutAction.OPEN_MENU_HOLD.holdMs.toLong()

    private val held = mutableSetOf<Int>()
    // Keys whose press reached the host. Their release is the host's too, whatever else happens.
    private val pressed = mutableSetOf<Int>()
    // Keys a shortcut claimed before their press reached the host, so their release must not act.
    private val consumed = mutableSetOf<Int>()
    // The key that completed a hold, its press held back until the hold is decided.
    private var deferred: Int? = null
    private var holdStartedAt = 0L
    // Chord keys held back until the chord either completes or clearly is not coming.
    private val waiting = mutableListOf<Int>()
    private var waitStartedAt = 0L
    private val replays = mutableListOf<Int>()

    val isBound: Boolean get() = chord.isNotEmpty() || hold.isNotEmpty()

    fun setShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        chord = shortcuts[ShortcutAction.OPEN_MENU].orEmpty()
        hold = shortcuts[ShortcutAction.OPEN_MENU_HOLD].orEmpty()
        reset()
    }

    fun reset() {
        held.clear()
        pressed.clear()
        consumed.clear()
        deferred = null
        waiting.clear()
        replays.clear()
    }

    /** Keys whose held-back press has to happen now, before the result of the call is acted on. */
    fun takeReplays(): List<Int> {
        if (replays.isEmpty()) return emptyList()
        val out = replays.toList()
        replays.clear()
        pressed += out
        return out
    }

    fun onKeyDown(keyCode: Int, now: Long, isRepeat: Boolean = false): Result {
        if (keyCode in consumed || keyCode == deferred || keyCode in waiting) return Result.CONSUME
        if (isRepeat) {
            // Still down from before this side was listening, such as a hold that began in the
            // game and opened the menu. Arming on it would count a hold nobody started here.
            return if (keyCode in hold && keyCode !in held) Result.CONSUME else Result.PASS
        }
        held += keyCode
        if (chord.isNotEmpty() && keyCode in chord && held.containsAll(chord)) {
            // The chord wins over a hold of fewer keys, so a start held on its way to select +
            // start never opens the menu a second time. Only keys whose press never reached the
            // host are claimed; one that did is released as the press it was.
            deferred = null
            waiting.clear()
            consumed += chord - pressed
            return Result.MENU
        }
        if (hold.isNotEmpty() && keyCode in hold && held.containsAll(hold)) {
            releaseWaiting()
            deferred = keyCode
            holdStartedAt = now
            return Result.CONSUME
        }
        // The first key of a chord says nothing yet: select on its own and select on its way to
        // select + start look the same until the next key, so its press waits for that.
        if (keyCode in chord && chord.size > 1 &&
            (waiting.isNotEmpty() || held.intersect(chord) == setOf(keyCode))
        ) {
            if (waiting.isEmpty()) waitStartedAt = now
            waiting += keyCode
            return Result.CONSUME
        }
        releaseWaiting()
        pressed += keyCode
        return Result.PASS
    }

    fun onKeyUp(keyCode: Int): Result {
        held -= keyCode
        pressed -= keyCode
        if (keyCode in waiting) {
            waiting.remove(keyCode)
            releaseWaiting()
            return Result.REPLAY_PRESS
        }
        if (keyCode == deferred) {
            deferred = null
            return Result.REPLAY_PRESS
        }
        if (consumed.remove(keyCode)) return Result.CONSUME
        return Result.PASS
    }

    /** When the host should next call [onTick], or null when nothing is waiting on time. */
    fun nextDeadline(): Long? {
        val hold = deferred?.let { holdStartedAt + holdMs }
        val wait = if (waiting.isNotEmpty()) waitStartedAt + CHORD_WAIT_MS else null
        return listOfNotNull(hold, wait).minOrNull()
    }

    /**
     * Time passing. [Result.MENU] once, when a hold has lasted long enough. A chord key held back
     * long enough is not waiting on a chord any more, so its press is released through
     * [takeReplays] and it is an ordinary held key from then on.
     */
    fun onTick(now: Long): Result {
        if (waiting.isNotEmpty() && now - waitStartedAt >= CHORD_WAIT_MS) releaseWaiting()
        val key = deferred ?: return Result.PASS
        if (now - holdStartedAt < holdMs) return Result.PASS
        deferred = null
        consumed += key
        return Result.MENU
    }

    private fun releaseWaiting() {
        replays += waiting
        waiting.clear()
    }

    companion object {
        /** How long a chord's first key waits for the rest before it is taken as a press. */
        const val CHORD_WAIT_MS = 300L
    }
}
