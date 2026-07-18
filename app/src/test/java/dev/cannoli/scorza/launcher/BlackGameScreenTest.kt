package dev.cannoli.scorza.launcher

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackGameScreenTest {
    @Test
    fun `black game screen is fully opt in and requires separate displays`() {
        assertTrue(shouldBlankGameScreen(true, true, true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(false, true, true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, false, true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, true, false, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, true, true, gameDisplayId = null, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, true, true, gameDisplayId = 4, launcherDisplayId = 4))
    }

    @Test
    fun `focus handoff waits for a completed tap`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_MOVE, 105f, 204f))
        assertTrue(detector.onTouch(MotionEvent.ACTION_UP, 105f, 204f))
    }

    @Test
    fun `swipes and canceled touches do not hand off focus`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_MOVE, 100f, 230f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 230f))

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_CANCEL, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 200f))
    }

    @Test
    fun `multi touch does not hand off focus`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_POINTER_DOWN, 100f, 200f, pointerCount = 2))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 200f))
    }
}
