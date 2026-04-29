package dev.cannoli.scorza.input

import android.view.InputDevice
import android.view.KeyEvent

object AndroidGamepadKeyNames {

    val DEFAULT_KEY_MAP: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to "btn_south",
        KeyEvent.KEYCODE_BUTTON_B to "btn_east",
        KeyEvent.KEYCODE_BUTTON_X to "btn_west",
        KeyEvent.KEYCODE_BUTTON_Y to "btn_north",
        KeyEvent.KEYCODE_BUTTON_L1 to "btn_l",
        KeyEvent.KEYCODE_BUTTON_R1 to "btn_r",
        KeyEvent.KEYCODE_BUTTON_L2 to "btn_l2",
        KeyEvent.KEYCODE_BUTTON_R2 to "btn_r2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "btn_l3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "btn_r3",
        KeyEvent.KEYCODE_BUTTON_START to "btn_start",
        KeyEvent.KEYCODE_BUTTON_SELECT to "btn_select",
        KeyEvent.KEYCODE_DPAD_UP to "btn_up",
        KeyEvent.KEYCODE_DPAD_DOWN to "btn_down",
        KeyEvent.KEYCODE_DPAD_LEFT to "btn_left",
        KeyEvent.KEYCODE_DPAD_RIGHT to "btn_right",
    )

    fun isGamepadEvent(event: KeyEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}
