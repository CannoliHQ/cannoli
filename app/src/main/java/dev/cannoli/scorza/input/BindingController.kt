package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject

/**
 * Generic chord-hold detector used by the launcher's "Bind shortcut" screen and the IGM
 * shortcut binding screen. Both wanted the same behavior: while listening, accumulate the
 * keycodes the user is holding, fire a tick every [TICK_MS], and commit the chord once the
 * user has held it for [HOLD_MS]. Releasing any held key cancels.
 *
 * The controller is screen-agnostic. Callers wire [onProgress] / [onCommit] / [onCancel] to
 * their own UI state and call [startListening] when entering capture mode.
 */
@ActivityScoped
class BindingController @Inject constructor() {

    companion object {
        const val HOLD_MS = 1500
        const val TICK_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
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
        handler.removeCallbacks(tickRunnable)
        onProgress(emptySet(), 0)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        heldKeys.clear()
        elapsedMs = 0
        handler.removeCallbacks(tickRunnable)
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
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
        onProgress(heldKeys.toSet(), 0)
        return true
    }

    /** Returns true when the keypress should be considered consumed. */
    fun keyUp(keyCode: Int): Boolean {
        if (!listening || keyCode !in heldKeys) return false
        stopListening()
        return true
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
                handler.removeCallbacks(this)
                onCommit(chord)
            } else {
                onProgress(heldKeys.toSet(), elapsedMs)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }
}
