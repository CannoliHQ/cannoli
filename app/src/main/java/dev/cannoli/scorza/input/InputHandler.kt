package dev.cannoli.scorza.input

import android.view.InputDevice
import android.view.KeyEvent

class InputHandler {

    var onUp: () -> Unit = {}
    var onDown: () -> Unit = {}
    var onLeft: () -> Unit = {}
    var onRight: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onStart: () -> Unit = {}
    var onL1: () -> Unit = {}
    var onR1: () -> Unit = {}
    var onL2: () -> Unit = {}
    var onR2: () -> Unit = {}
    var onL3: () -> Unit = {}
    var onR3: () -> Unit = {}
    var onWest: () -> Unit = {}
    var onNorth: () -> Unit = {}
    var onMenu: () -> Unit = {}

    var swapConfirmBack: Boolean = true

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return false

        val button = resolveButton(event)
        if (button != null) return dispatchButton(button)

        val isGamepad = isGamepadEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (isGamepad) false else { onConfirm(); true }
            KeyEvent.KEYCODE_BACK -> true
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ESCAPE -> if (isGamepad) false else { onBack(); true }
            else -> false
        }
    }

    fun resolveButton(keyCode: Int): String? = DEFAULT_KEY_MAP[keyCode]

    fun resolveButton(event: KeyEvent): String? = resolveButton(event.keyCode)

    private fun dispatchButton(button: String): Boolean {
        when (button) {
            "btn_up" -> onUp()
            "btn_down" -> onDown()
            "btn_left" -> onLeft()
            "btn_right" -> onRight()
            "btn_south" -> if (swapConfirmBack) onBack() else onConfirm()
            "btn_east" -> if (swapConfirmBack) onConfirm() else onBack()
            "btn_west" -> onWest()
            "btn_north" -> onNorth()
            "btn_select" -> onSelect()
            "btn_start" -> onStart()
            "btn_l" -> onL1()
            "btn_r" -> onR1()
            "btn_l2" -> onL2()
            "btn_r2" -> onR2()
            "btn_l3" -> onL3()
            "btn_r3" -> onR3()
            "btn_menu" -> onMenu()
            else -> return false
        }
        return true
    }

    companion object {
        val DEFAULT_KEY_MAP = mapOf(
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
}
