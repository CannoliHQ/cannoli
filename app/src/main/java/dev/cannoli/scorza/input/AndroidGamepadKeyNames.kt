package dev.cannoli.scorza.input

import android.view.InputDevice
import android.view.KeyEvent

object AndroidGamepadKeyNames {

    val DEFAULT_KEY_MAP: Map<Int, CanonicalButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to CanonicalButton.BTN_SOUTH,
        KeyEvent.KEYCODE_BUTTON_B to CanonicalButton.BTN_EAST,
        KeyEvent.KEYCODE_BUTTON_X to CanonicalButton.BTN_WEST,
        KeyEvent.KEYCODE_BUTTON_Y to CanonicalButton.BTN_NORTH,
        KeyEvent.KEYCODE_BUTTON_L1 to CanonicalButton.BTN_L,
        KeyEvent.KEYCODE_BUTTON_R1 to CanonicalButton.BTN_R,
        KeyEvent.KEYCODE_BUTTON_L2 to CanonicalButton.BTN_L2,
        KeyEvent.KEYCODE_BUTTON_R2 to CanonicalButton.BTN_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL to CanonicalButton.BTN_L3,
        KeyEvent.KEYCODE_BUTTON_THUMBR to CanonicalButton.BTN_R3,
        KeyEvent.KEYCODE_BUTTON_START to CanonicalButton.BTN_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to CanonicalButton.BTN_SELECT,
        KeyEvent.KEYCODE_DPAD_UP to CanonicalButton.BTN_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to CanonicalButton.BTN_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to CanonicalButton.BTN_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to CanonicalButton.BTN_RIGHT,
        KeyEvent.KEYCODE_BACK to CanonicalButton.BTN_MENU,
        KeyEvent.KEYCODE_BUTTON_MODE to CanonicalButton.BTN_MENU,
    )

    fun isGamepadEvent(event: KeyEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}
