package dev.cannoli.scorza.onboarding

import android.view.KeyEvent

/**
 * The press that advanced the welcome step. Whichever device sent it is the controller the player
 * is holding, which is what the input mapping work needs from a first step.
 */
data class WelcomePress(val deviceId: Int, val keyCode: Int)

// Not controller input, so the welcome step's run of presses neither counts them nor is broken by
// them: someone turning the volume down three times must not be judged as answering the question.
// Everything else participates, because a press of a different button is exactly what breaks a run,
// and a key the step never sees can never break one.
private val SYSTEM_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MUTE,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_WAKEUP,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_HEADSETHOOK,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    KeyEvent.KEYCODE_MEDIA_RECORD,
    KeyEvent.KEYCODE_MEDIA_EJECT,
    KeyEvent.KEYCODE_MEDIA_CLOSE,
    KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
)

fun isSystemKey(keyCode: Int): Boolean = keyCode in SYSTEM_KEYS
