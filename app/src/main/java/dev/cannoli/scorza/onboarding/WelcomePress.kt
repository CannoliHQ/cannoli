package dev.cannoli.scorza.onboarding

import android.view.KeyEvent

data class WelcomePress(val deviceId: Int, val keyCode: Int)

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
