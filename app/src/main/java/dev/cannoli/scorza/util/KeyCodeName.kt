package dev.cannoli.scorza.util

import android.view.KeyEvent
import java.util.Locale

fun keyCodeName(keyCode: Int): String {
    if (keyCode == -1) return "UNMAPPED"
    return KeyEvent.keyCodeToString(keyCode)
        .removePrefix("KEYCODE_")
        .replace("BUTTON_", "")
        .split("_")
        .joinToString(" ") { word -> word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } }
        .replace("Dpad ", "D-Pad ")
}
