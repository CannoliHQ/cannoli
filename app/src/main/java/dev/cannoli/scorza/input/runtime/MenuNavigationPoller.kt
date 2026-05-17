package dev.cannoli.scorza.input.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.CanonicalButton
import javax.inject.Inject

/**
 * Held-state poller that drives navigation auto-repeat. Reads `PortRouter.isCanonicalPressedAt`
 * across all ports for BTN_UP / BTN_DOWN / BTN_LEFT / BTN_RIGHT; on a direction transition from
 * none-held to held, waits [INITIAL_DELAY_MS], then fires the matching dispatcher callback every
 * [REPEAT_INTERVAL_MS]. Source-agnostic: works for keycode-bound, hat-bound, or stick-bound
 * navigation as long as the canonical is tracked in `PortRouter`.
 *
 * Initial press fires synchronously via `InputDispatcher.handleKeyEvent`; the poller only handles
 * auto-repeat (it skips the first fire on transition).
 */
@ActivityScoped
class MenuNavigationPoller @Inject constructor(
    private val portRouter: PortRouter,
    private val dispatcher: InputDispatcher,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var heldDir: CanonicalButton? = null
    private var nextFireAt = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val current = readDir()
            if (current != heldDir) {
                heldDir = current
                if (current != null) nextFireAt = now + INITIAL_DELAY_MS
            } else if (current != null && now >= nextFireAt) {
                fire(current)
                nextFireAt = now + REPEAT_INTERVAL_MS
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        heldDir = null
        nextFireAt = 0L
    }

    private fun readDir(): CanonicalButton? {
        if (anyPortHas(CanonicalButton.BTN_UP)) return CanonicalButton.BTN_UP
        if (anyPortHas(CanonicalButton.BTN_DOWN)) return CanonicalButton.BTN_DOWN
        if (anyPortHas(CanonicalButton.BTN_LEFT)) return CanonicalButton.BTN_LEFT
        if (anyPortHas(CanonicalButton.BTN_RIGHT)) return CanonicalButton.BTN_RIGHT
        return null
    }

    private fun anyPortHas(btn: CanonicalButton): Boolean {
        for (port in 0 until MAX_PORTS) {
            if (portRouter.isCanonicalPressedAt(port, btn)) return true
        }
        return false
    }

    private fun fire(dir: CanonicalButton) {
        when (dir) {
            CanonicalButton.BTN_UP -> dispatcher.onUp()
            CanonicalButton.BTN_DOWN -> dispatcher.onDown()
            CanonicalButton.BTN_LEFT -> dispatcher.onLeft()
            CanonicalButton.BTN_RIGHT -> dispatcher.onRight()
            else -> Unit
        }
    }

    companion object {
        private const val INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 80L
        private const val POLL_INTERVAL_MS = 33L
        private const val MAX_PORTS = 4
    }
}
