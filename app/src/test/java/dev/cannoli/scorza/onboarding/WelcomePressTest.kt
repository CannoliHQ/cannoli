package dev.cannoli.scorza.onboarding

import android.view.KeyEvent
import dev.cannoli.scorza.input.HatKeySync
import dev.cannoli.scorza.input.legend.CONFIRM_PRESSES_REQUIRED
import dev.cannoli.scorza.input.legend.ConfirmPressCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAD = 1

class WelcomePressTest {

    private val counter = ConfirmPressCounter()

    // The welcome step's rule, in the order MainActivity applies it: a system key is not seen at
    // all, everything else is a press in the run.
    private fun feed(keyCode: Int, deviceId: Int = PAD): Boolean =
        if (isSystemKey(keyCode)) false else counter.press(deviceId, keyCode)

    @Test fun everyFaceButtonAndShoulderIsAPress() {
        listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
        ).forEach { assertFalse("$it should reach the counter", isSystemKey(it)) }
    }

    @Test fun everyDpadDirectionIsAPress() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        ).forEach { assertFalse("$it should reach the counter", isSystemKey(it)) }
    }

    @Test fun theKeysHandheldsWireToGpioArePresses() {
        listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU,
        ).forEach { assertFalse("$it should reach the counter", isSystemKey(it)) }
    }

    @Test fun systemKeysAreNotPresses() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        ).forEach { assertTrue("$it should be ignored", isSystemKey(it)) }
    }

    // The bug this replaced: the d-pad failed the old inclusion gate, so it never reached the
    // counter and a run it should have broken completed anyway.
    @Test fun aDpadPressBetweenConfirmsBreaksTheRun() {
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0, counter.count.value)

        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(feed(KeyEvent.KEYCODE_BUTTON_A))
    }

    // The pads that actually shipped the bug report the D-pad as hat axes and never send a
    // KEYCODE_DPAD_*, so the direction has to reach the run through the hat sync instead.
    @Test fun aHatDirectionBetweenConfirmsBreaksTheRun() {
        val hats = HatKeySync()
        feed(KeyEvent.KEYCODE_BUTTON_A)
        hats.sync(deviceId = PAD, hatX = 0f, hatY = 1f, onDown = { feed(it) }, onUp = {})
        assertEquals(0, counter.count.value)

        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(feed(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun anyOtherButtonBetweenConfirmsBreaksTheRun() {
        feed(KeyEvent.KEYCODE_BUTTON_A)
        feed(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(0, counter.count.value)
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1, counter.count.value)
    }

    @Test fun aSystemKeyBetweenConfirmsLeavesTheRunUntouched() {
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(1, counter.count.value)
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(CONFIRM_PRESSES_REQUIRED, counter.count.value)
    }

    @Test fun adjustingTheVolumeThreeTimesNeverCompletesARun() {
        repeat(3) { assertFalse(feed(KeyEvent.KEYCODE_VOLUME_UP)) }
        assertEquals(0, counter.count.value)
    }

    @Test fun aSecondPadPressingTheSameButtonBreaksTheRun() {
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A, deviceId = PAD + 1))
        assertEquals(0, counter.count.value)
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(feed(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(feed(KeyEvent.KEYCODE_BUTTON_A))
    }
}
