package dev.cannoli.scorza.input.runtime

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject

/**
 * Stick-axis direction tracker with auto-repeat for menu navigation. Reads `AXIS_X` / `AXIS_Y`
 * from motion events; on a direction transition fires the matching dispatcher callback
 * (`onUp`/`onDown`/`onLeft`/`onRight`) and posts a runnable to re-fire at [REPEAT_INTERVAL_MS]
 * after an [INITIAL_DELAY_MS] hold.
 *
 * Lives outside the dispatcher because default mappings bind sticks to analog roles
 * (LEFT_STICK_X/Y), not to canonical directional buttons -- so the dispatcher's evaluator
 * never produces a Pressed(BTN_DOWN) delta for raw stick motion, and the held-state poller
 * never sees the canonical held. This helper provides the missing stick-to-navigation path.
 *
 * Activities call [handleMotion] from their motion event hook and [stop] from `onPause` so a
 * runnable doesn't keep firing into the other activity's wired callbacks after a transition.
 */
@ActivityScoped
class StickAutoRepeat @Inject constructor(
    private val dispatcher: InputDispatcher,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var heldDir = 0
    private val runnable = object : Runnable {
        override fun run() {
            fireForDir(heldDir)
            if (heldDir != 0) handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    fun handleMotion(event: MotionEvent, dispatcherHandled: Boolean = false) {
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        val newDir = when {
            stickY < -0.5f -> DIR_UP
            stickY > 0.5f -> DIR_DOWN
            stickX < -0.5f -> DIR_LEFT
            stickX > 0.5f -> DIR_RIGHT
            else -> 0
        }
        if (newDir != heldDir) {
            handler.removeCallbacks(runnable)
            heldDir = newDir
            // When the dispatcher already handled this motion (stick is bound as a digital
            // direction), the canonical's auto-repeat is owned by MenuNavigationPoller. Firing
            // here would double-scroll, so we only track the transition.
            if (newDir != 0 && !dispatcherHandled) {
                fireForDir(newDir)
                handler.postDelayed(runnable, INITIAL_DELAY_MS)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(runnable)
        heldDir = 0
    }

    private fun fireForDir(dir: Int) {
        when (dir) {
            DIR_UP -> dispatcher.onUp()
            DIR_DOWN -> dispatcher.onDown()
            DIR_LEFT -> dispatcher.onLeft()
            DIR_RIGHT -> dispatcher.onRight()
        }
    }

    companion object {
        private const val DIR_UP = 1
        private const val DIR_DOWN = 2
        private const val DIR_LEFT = 3
        private const val DIR_RIGHT = 4
        private const val INITIAL_DELAY_MS = 280L
        private const val REPEAT_INTERVAL_MS = 80L
    }
}
