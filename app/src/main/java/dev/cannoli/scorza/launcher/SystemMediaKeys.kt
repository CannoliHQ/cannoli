package dev.cannoli.scorza.launcher

import android.view.KeyEvent

/** Keys that must remain owned by Android instead of launcher focus or gameplay routing. */
internal fun isSystemMediaKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
    else -> false
}
