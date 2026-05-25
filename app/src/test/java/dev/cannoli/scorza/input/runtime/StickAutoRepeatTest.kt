package dev.cannoli.scorza.input.runtime

import android.view.MotionEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickAutoRepeatTest {

    private lateinit var dispatcher: InputDispatcher
    private lateinit var autoRepeat: StickAutoRepeat

    @Before fun setup() {
        dispatcher = mockk(relaxed = true)
        autoRepeat = StickAutoRepeat(dispatcher)
    }

    private fun motion(stickX: Float = 0f, stickY: Float = 0f): MotionEvent {
        val ev = mockk<MotionEvent>()
        every { ev.getAxisValue(MotionEvent.AXIS_X) } returns stickX
        every { ev.getAxisValue(MotionEvent.AXIS_Y) } returns stickY
        return ev
    }

    @Test fun `analog-only stick transition fires immediately when dispatcher did not handle`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = false)
        verify(exactly = 1) { dispatcher.onDown() }
    }

    @Test fun `digital-bound stick transition does not double-fire when dispatcher already handled it`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        verify(exactly = 0) { dispatcher.onDown() }
    }

    @Test fun `dispatcher-handled transition still updates heldDir so next sustained frame does not fire`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        autoRepeat.handleMotion(motion(stickY = 0.8f), dispatcherHandled = false)
        verify(exactly = 0) { dispatcher.onDown() }
    }

    @Test fun `release back to neutral after dispatcher-handled hold does not fire`() {
        autoRepeat.handleMotion(motion(stickY = 0.7f), dispatcherHandled = true)
        autoRepeat.handleMotion(motion(stickY = 0f), dispatcherHandled = false)
        verify(exactly = 0) { dispatcher.onDown() }
        verify(exactly = 0) { dispatcher.onUp() }
    }
}
