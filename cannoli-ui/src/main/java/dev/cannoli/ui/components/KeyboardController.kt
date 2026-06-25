package dev.cannoli.ui.components

import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.KEY_SHIFT
import dev.cannoli.ui.KEY_SPACE
import dev.cannoli.ui.KEY_SYMBOLS

val KEYBOARD_ALPHA = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", KEY_BACKSPACE),
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", KEY_ENTER),
    listOf(KEY_SHIFT, "z", "x", "c", "v", "b", "n", "m", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

val KEYBOARD_ALPHA_SHIFTED = listOf(
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", KEY_BACKSPACE),
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", KEY_ENTER),
    listOf(KEY_SHIFT, "Z", "X", "C", "V", "B", "N", "M", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

val KEYBOARD_SYMBOLS = listOf(
    listOf("~", "`", "|", "\\", "<", ">", "{", "}", "[", "]", KEY_BACKSPACE),
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
    listOf("-", "_", "=", "+", ";", ":", "'", "\"", "?", KEY_ENTER),
    listOf(KEY_SHIFT, ",", ".", "/", "\\", "|", "~", "`", KEY_SYMBOLS),
    listOf(KEY_SPACE)
)

fun getKeyboardRows(caps: Boolean, symbols: Boolean): List<List<String>> = when {
    symbols -> KEYBOARD_SYMBOLS
    caps -> KEYBOARD_ALPHA_SHIFTED
    else -> KEYBOARD_ALPHA
}

data class KeyboardState(
    val text: String = "",
    val cursorPos: Int = 0,
    val keyRow: Int = 2,
    val keyCol: Int = 0,
    val caps: Boolean = false,
    val symbols: Boolean = false,
)

enum class Direction { UP, DOWN, LEFT, RIGHT }

sealed interface KeyboardPress {
    data class Update(val state: KeyboardState) : KeyboardPress
    data object Confirm : KeyboardPress
}

object KeyboardController {

    fun moveSelection(s: KeyboardState, dir: Direction): KeyboardState {
        val rows = getKeyboardRows(s.caps, s.symbols)
        return when (dir) {
            Direction.UP -> {
                val newRow = if (s.keyRow <= 0) rows.lastIndex else s.keyRow - 1
                s.copy(keyRow = newRow, keyCol = s.keyCol.coerceAtMost(rows[newRow].lastIndex))
            }
            Direction.DOWN -> {
                val newRow = if (s.keyRow >= rows.lastIndex) 0 else s.keyRow + 1
                s.copy(keyRow = newRow, keyCol = s.keyCol.coerceAtMost(rows[newRow].lastIndex))
            }
            Direction.LEFT -> {
                val rowSize = rows[s.keyRow.coerceIn(0, rows.lastIndex)].size
                s.copy(keyCol = if (s.keyCol <= 0) rowSize - 1 else s.keyCol - 1)
            }
            Direction.RIGHT -> {
                val rowSize = rows[s.keyRow.coerceIn(0, rows.lastIndex)].size
                s.copy(keyCol = if (s.keyCol >= rowSize - 1) 0 else s.keyCol + 1)
            }
        }
    }

    fun moveCursor(s: KeyboardState, delta: Int): KeyboardState =
        s.copy(cursorPos = (s.cursorPos + delta).coerceIn(0, s.text.length))

    fun cursorToStart(s: KeyboardState): KeyboardState = s.copy(cursorPos = 0)

    fun cursorToEnd(s: KeyboardState): KeyboardState = s.copy(cursorPos = s.text.length)

    fun toggleCaps(s: KeyboardState): KeyboardState = s.copy(caps = !s.caps)

    fun toggleSymbols(s: KeyboardState): KeyboardState = s.copy(symbols = !s.symbols)

    fun insertChar(s: KeyboardState, char: String): KeyboardState {
        val pos = s.cursorPos.coerceIn(0, s.text.length)
        return s.copy(text = s.text.substring(0, pos) + char + s.text.substring(pos), cursorPos = pos + 1)
    }

    fun backspace(s: KeyboardState): KeyboardState {
        if (s.cursorPos <= 0) return s
        val pos = s.cursorPos.coerceIn(1, s.text.length)
        return s.copy(text = s.text.removeRange(pos - 1, pos), cursorPos = pos - 1)
    }

    fun press(s: KeyboardState): KeyboardPress {
        val rows = getKeyboardRows(s.caps, s.symbols)
        val key = rows.getOrNull(s.keyRow)?.getOrNull(s.keyCol) ?: return KeyboardPress.Update(s)
        return when (key) {
            KEY_SHIFT -> KeyboardPress.Update(toggleCaps(s))
            KEY_SYMBOLS -> KeyboardPress.Update(toggleSymbols(s))
            KEY_ENTER -> KeyboardPress.Confirm
            KEY_BACKSPACE -> KeyboardPress.Update(backspace(s))
            KEY_SPACE -> KeyboardPress.Update(insertChar(s, " "))
            else -> KeyboardPress.Update(insertChar(s, key))
        }
    }
}
