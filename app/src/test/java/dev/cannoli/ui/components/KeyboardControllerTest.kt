package dev.cannoli.ui.components

import dev.cannoli.ui.KEY_SHIFT
import dev.cannoli.ui.KEY_SPACE
import dev.cannoli.ui.KEY_SYMBOLS
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardControllerTest {

    @Test fun `up from row 0 wraps to last row`() {
        val rows = getKeyboardRows(caps = false, symbols = false)
        val s = KeyboardController.moveSelection(KeyboardState(keyRow = 0, keyCol = 0), Direction.UP)
        assertEquals(rows.lastIndex, s.keyRow)
    }

    @Test fun `down from last row wraps to 0`() {
        val rows = getKeyboardRows(caps = false, symbols = false)
        val s = KeyboardController.moveSelection(KeyboardState(keyRow = rows.lastIndex, keyCol = 0), Direction.DOWN)
        assertEquals(0, s.keyRow)
    }

    @Test fun `up clamps column to the new row length`() {
        val s = KeyboardController.moveSelection(KeyboardState(keyRow = 0, keyCol = 10), Direction.UP)
        assertEquals(0, s.keyCol)
    }

    @Test fun `left from col 0 wraps to row end`() {
        val rows = getKeyboardRows(caps = false, symbols = false)
        val s = KeyboardController.moveSelection(KeyboardState(keyRow = 1, keyCol = 0), Direction.LEFT)
        assertEquals(rows[1].lastIndex, s.keyCol)
    }

    @Test fun `right from row end wraps to col 0`() {
        val rows = getKeyboardRows(caps = false, symbols = false)
        val s = KeyboardController.moveSelection(KeyboardState(keyRow = 1, keyCol = rows[1].lastIndex), Direction.RIGHT)
        assertEquals(0, s.keyCol)
    }

    @Test fun `moveCursor clamps at start and end`() {
        assertEquals(0, KeyboardController.moveCursor(KeyboardState(text = "abc", cursorPos = 0), -1).cursorPos)
        assertEquals(3, KeyboardController.moveCursor(KeyboardState(text = "abc", cursorPos = 3), 1).cursorPos)
    }

    @Test fun `cursorToStart and cursorToEnd`() {
        assertEquals(0, KeyboardController.cursorToStart(KeyboardState(text = "abc", cursorPos = 2)).cursorPos)
        assertEquals(3, KeyboardController.cursorToEnd(KeyboardState(text = "abc", cursorPos = 0)).cursorPos)
    }

    @Test fun `insertChar at cursor advances`() {
        val s = KeyboardController.insertChar(KeyboardState(text = "ac", cursorPos = 1), "b")
        assertEquals("abc", s.text)
        assertEquals(2, s.cursorPos)
    }

    @Test fun `backspace removes char before cursor`() {
        val s = KeyboardController.backspace(KeyboardState(text = "abc", cursorPos = 2))
        assertEquals("ac", s.text)
        assertEquals(1, s.cursorPos)
    }

    @Test fun `backspace at start is no-op`() {
        val s0 = KeyboardState(text = "abc", cursorPos = 0)
        assertEquals(s0, KeyboardController.backspace(s0))
    }

    @Test fun `press enter confirms`() {
        val s = KeyboardState(text = "x", cursorPos = 1, keyRow = 2, keyCol = 9)
        assertEquals(KeyboardPress.Confirm, KeyboardController.press(s))
    }

    @Test fun `press letter inserts at cursor`() {
        val r = KeyboardController.press(KeyboardState(text = "", cursorPos = 0, keyRow = 1, keyCol = 0)) as KeyboardPress.Update
        assertEquals("q", r.state.text)
        assertEquals(1, r.state.cursorPos)
    }

    @Test fun `press shift toggles caps`() {
        val r = KeyboardController.press(KeyboardState(keyRow = 3, keyCol = 0)) as KeyboardPress.Update
        assertEquals(true, r.state.caps)
    }

    @Test fun `press symbols toggles symbols`() {
        val r = KeyboardController.press(KeyboardState(keyRow = 3, keyCol = 8)) as KeyboardPress.Update
        assertEquals(true, r.state.symbols)
    }

    @Test fun `press space inserts a space`() {
        val r = KeyboardController.press(KeyboardState(text = "a", cursorPos = 1, keyRow = 4, keyCol = 0)) as KeyboardPress.Update
        assertEquals("a ", r.state.text)
    }

    @Test fun `press backspace key deletes before cursor`() {
        val r = KeyboardController.press(KeyboardState(text = "ab", cursorPos = 2, keyRow = 0, keyCol = 10)) as KeyboardPress.Update
        assertEquals("a", r.state.text)
    }

    @Test fun `layout capability flags`() {
        assertEquals(true, KeyboardLayout.Default.supportsCaps)
        assertEquals(true, KeyboardLayout.Default.supportsSymbols)
        assertEquals(true, KeyboardLayout.Default.supportsSpace)
        assertEquals(false, KeyboardLayout.Number.supportsCaps)
        assertEquals(false, KeyboardLayout.Number.supportsSymbols)
        assertEquals(false, KeyboardLayout.Number.supportsSpace)
    }

    @Test fun `number layout ignores caps and symbols`() {
        assertEquals(KEYBOARD_NUMBER, getKeyboardRows(KeyboardLayout.Number, caps = true, symbols = true))
        assertEquals(KEYBOARD_NUMBER, getKeyboardRows(KeyboardLayout.Number, caps = false, symbols = false))
    }

    @Test fun `number layout has no modifier keys`() {
        val flat = KEYBOARD_NUMBER.flatten()
        assertEquals(false, flat.contains(KEY_SHIFT))
        assertEquals(false, flat.contains(KEY_SYMBOLS))
        assertEquals(false, flat.contains(KEY_SPACE))
    }

    @Test fun `number layout press inserts digit`() {
        val s = KeyboardState(layout = KeyboardLayout.Number, keyRow = 0, keyCol = 0)
        val r = KeyboardController.press(s) as KeyboardPress.Update
        assertEquals("1", r.state.text)
    }

    @Test fun `number layout enter confirms`() {
        val s = KeyboardState(layout = KeyboardLayout.Number, keyRow = 3, keyCol = 2)
        assertEquals(KeyboardPress.Confirm, KeyboardController.press(s))
    }

    @Test fun `number layout backspace key deletes before cursor`() {
        val s = KeyboardState(text = "12", cursorPos = 2, layout = KeyboardLayout.Number, keyRow = 3, keyCol = 0)
        val r = KeyboardController.press(s) as KeyboardPress.Update
        assertEquals("1", r.state.text)
    }
}
