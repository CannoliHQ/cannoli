package dev.cannoli.scorza.onboarding

import android.view.KeyEvent

/**
 * The press that advanced the welcome step. Whichever device sent it is the controller the player
 * is holding, which is what the input mapping work needs from a first step.
 */
data class WelcomePress(val deviceId: Int, val keyCode: Int)

// Face buttons and shoulders occupy one contiguous keycode block; the rest are the d-pad and the
// keyboard-sourced keys handhelds wire their GPIO buttons to. Same set the wizard's unmapped-device
// fallback in MainActivity already understands, so no new notion of "a button" is introduced.
private val WELCOME_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_ESCAPE,
    KeyEvent.KEYCODE_MENU,
)

fun isWelcomeAdvanceKey(keyCode: Int): Boolean =
    keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE || keyCode in WELCOME_KEYS
