package dev.cannoli.igm

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

interface BindingTicker {
    fun post(delayMs: Long, action: Runnable)
    fun cancel(action: Runnable)
}

private class HandlerTicker : BindingTicker {
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

    fun keyDown(keyCode: Int): Boolean {
        if (!listening) return false
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true
        if (keyCode in heldKeys) return true
        heldKeys.add(keyCode)
        elapsedMs = 0
        ticker.cancel(tickRunnable)
        ticker.post(TICK_MS, tickRunnable)
        onProgress(heldKeys.toSet(), 0)
        return true
    }

    fun keyUp(keyCode: Int): Boolean {
        if (!listening || keyCode !in heldKeys) return false
        stopListening()
        return true
    }

    internal fun useTickerForTest(replacement: BindingTicker) { ticker = replacement }

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
