package dev.cannoli.igm

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * The chord-hold detector behind both binding screens: the launcher's and the in-game menu's.
 *
 * While listening it accumulates the keycodes being held, ticks every [TICK_MS], and commits once
 * they have been held together for [HOLD_MS]. Releasing any of them cancels, which is what makes a
 * mistaken press cost nothing.
 *
 * Screen-agnostic and free of injection so both processes can hold one: the in-game menu runs in
 * the emulator's process, where the launcher's object graph does not reach. Callers wire
 * [onProgress] / [onCommit] / [onCancel] to their own state.
 */
/**
 * How the detector waits. Real callers tick on the main thread; a test drives [BindingController]
 * directly and wants no thread at all, which is otherwise unreachable because the hold is a timer.
 */
interface BindingTicker {
    fun post(delayMs: Long, action: Runnable)
    fun cancel(action: Runnable)
}

private class HandlerTicker : BindingTicker {
    // Built on first use, not on construction: the in-game menu holds a controller from its own,
    // which unit tests build with no Looper behind them.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun post(delayMs: Long, action: Runnable) { handler.postDelayed(action, delayMs) }
    override fun cancel(action: Runnable) { handler.removeCallbacks(action) }
}

class BindingController(private var ticker: BindingTicker = HandlerTicker()) {

    companion object {
        const val HOLD_MS = 1500
        const val TICK_MS = 100L
    }

    private var listening = false
    private val heldKeys = mutableSetOf<Int>()
    private var elapsedMs = 0

    var onProgress: (heldKeys: Set<Int>, elapsedMs: Int) -> Unit = { _, _ -> }
    var onCommit: (chord: Set<Int>) -> Unit = {}
    var onCancel: () -> Unit = {}

    val isListening: Boolean get() = listening

    fun startListening() {
        listening = true
        heldKeys.clear()
        elapsedMs = 0
        ticker.cancel(tickRunnable)
        onProgress(emptySet(), 0)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        heldKeys.clear()
        elapsedMs = 0
        ticker.cancel(tickRunnable)
        onCancel()
    }

    /** Returns true when the keypress should be considered consumed. */
    fun keyDown(keyCode: Int): Boolean {
        if (!listening) return false
        // Canonical (hat/stick-sourced) callbacks carry no keycode. Swallow them rather than
        // recording KEYCODE_UNKNOWN, which would store an unmatchable "Unknown" chord member.
        // Hat D-pads reach the chord as real KEYCODE_DPAD_* via HatKeySync.
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true
        if (keyCode in heldKeys) return true
        heldKeys.add(keyCode)
        elapsedMs = 0
        ticker.cancel(tickRunnable)
        ticker.post(TICK_MS, tickRunnable)
        onProgress(heldKeys.toSet(), 0)
        return true
    }

    /** Returns true when the keypress should be considered consumed. */
    fun keyUp(keyCode: Int): Boolean {
        if (!listening || keyCode !in heldKeys) return false
        stopListening()
        return true
    }

    /** Swaps the wait out, so a test can drive the hold without a thread behind it. */
    internal fun useTickerForTest(replacement: BindingTicker) { ticker = replacement }

    /** Commits whatever is held now, so the hold can be exercised without waiting out its timer. */
    internal fun forceCommitForTest() {
        if (!listening) return
        val chord = heldKeys.toSet()
        listening = false
        heldKeys.clear()
        elapsedMs = 0
        onCommit(chord)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!listening) return
            elapsedMs += TICK_MS.toInt()
            if (elapsedMs >= HOLD_MS) {
                val chord = heldKeys.toSet()
                listening = false
                heldKeys.clear()
                elapsedMs = 0
                ticker.cancel(this)
                onCommit(chord)
            } else {
                onProgress(heldKeys.toSet(), elapsedMs)
                ticker.post(TICK_MS, this)
            }
        }
    }
}
