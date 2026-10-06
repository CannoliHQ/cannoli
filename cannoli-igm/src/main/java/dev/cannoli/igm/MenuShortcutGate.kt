package dev.cannoli.igm

class MenuShortcutGate {

    enum class Result {
        PASS,

        CONSUME,

        MENU,

        REPLAY_PRESS,
    }

    private var chord: Set<Int> = emptySet()
    private var hold: Set<Int> = emptySet()
    private var holdMs: Long = ShortcutAction.OPEN_MENU_HOLD.holdMs.toLong()

    private val held = mutableSetOf<Int>()
    private val pressed = mutableSetOf<Int>()
    private val consumed = mutableSetOf<Int>()
    private var deferred: Int? = null
    private var holdStartedAt = 0L
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
            return if (keyCode in hold && keyCode !in held) Result.CONSUME else Result.PASS
        }
        held += keyCode
        if (chord.isNotEmpty() && keyCode in chord && held.containsAll(chord)) {
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

    fun nextDeadline(): Long? {
        val hold = deferred?.let { holdStartedAt + holdMs }
        val wait = if (waiting.isNotEmpty()) waitStartedAt + CHORD_WAIT_MS else null
        return listOfNotNull(hold, wait).minOrNull()
    }

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
        const val CHORD_WAIT_MS = 300L
    }
}
