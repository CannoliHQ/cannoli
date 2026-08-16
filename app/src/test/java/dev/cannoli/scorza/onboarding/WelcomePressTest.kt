package dev.cannoli.scorza.onboarding

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomePressTest {

    @Test fun everyFaceButtonAndShoulderAdvances() {
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
        ).forEach { assertTrue("$it should advance", isWelcomeAdvanceKey(it)) }
    }

    @Test fun everyDpadDirectionAdvances() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        ).forEach { assertTrue("$it should advance", isWelcomeAdvanceKey(it)) }
    }

    @Test fun theKeysHandheldsWireToGpioAdvance() {
        listOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU,
        ).forEach { assertTrue("$it should advance", isWelcomeAdvanceKey(it)) }
    }

    @Test fun systemKeysDoNotAdvance() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_A,
        ).forEach { assertFalse("$it should not advance", isWelcomeAdvanceKey(it)) }
    }
}
