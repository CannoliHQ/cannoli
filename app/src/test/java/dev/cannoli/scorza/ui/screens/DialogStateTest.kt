package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.components.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialogStateTest {

    @Test fun `menu delta empty options returns null instead of dividing by zero`() {
        val menu = DialogState.ContextMenu(gameName = "g", options = emptyList())
        assertNull(menu.withMenuDelta(1))
        assertNull(menu.withMenuDelta(-1))

        val bulk = DialogState.BulkContextMenu(gamePaths = emptyList(), options = emptyList())
        assertNull(bulk.withMenuDelta(1))
    }

    @Test fun `menu delta moves selection within bounds`() {
        val menu = DialogState.ContextMenu(gameName = "g", selectedOption = 0, options = listOf("a", "b", "c"))
        val moved = menu.withMenuDelta(1) as DialogState.ContextMenu
        assertEquals(1, moved.selectedOption)
    }

    @Test fun `menu delta wraps forward past the end`() {
        val menu = DialogState.ContextMenu(gameName = "g", selectedOption = 2, options = listOf("a", "b", "c"))
        val wrapped = menu.withMenuDelta(1) as DialogState.ContextMenu
        assertEquals(0, wrapped.selectedOption)
    }

    @Test fun `menu delta wraps backward past zero`() {
        val menu = DialogState.ContextMenu(gameName = "g", selectedOption = 0, options = listOf("a", "b", "c"))
        val wrapped = menu.withMenuDelta(-1) as DialogState.ContextMenu
        assertEquals(2, wrapped.selectedOption)
    }

    @Test fun `menu delta on bulk menu also wraps`() {
        val bulk = DialogState.BulkContextMenu(
            gamePaths = listOf("/x"), selectedOption = 1, options = listOf("a", "b")
        )
        val wrapped = bulk.withMenuDelta(2) as DialogState.BulkContextMenu
        assertEquals(1, wrapped.selectedOption)
    }

    @Test fun `menu delta on non-menu states returns null`() {
        assertNull(DialogState.None.withMenuDelta(1))
        assertNull(DialogState.QuitConfirm.withMenuDelta(1))
        assertNull(DialogState.MissingCore("x").withMenuDelta(1))
    }

    @Test fun `backspace removes the character before the cursor`() {
        val state = DialogState.RenameInput(target = RenameTarget.GameListItem, keyboard = KeyboardState(text = "hello", cursorPos = 5))
        val ks = KeyboardController.backspace(state.keyboard)
        assertEquals("hell", ks.text)
        assertEquals(4, ks.cursorPos)
    }

    @Test fun `backspace from middle of string removes correct character`() {
        val state = DialogState.RenameInput(target = RenameTarget.GameListItem, keyboard = KeyboardState(text = "abcdef", cursorPos = 3))
        val ks = KeyboardController.backspace(state.keyboard)
        assertEquals("abdef", ks.text)
        assertEquals(2, ks.cursorPos)
    }

    @Test fun `backspace at position zero is a no-op`() {
        val ks0 = KeyboardState(text = "abc", cursorPos = 0)
        assertEquals(ks0, KeyboardController.backspace(ks0))
    }

    @Test fun `insert char at end appends to name`() {
        val ks = KeyboardController.insertChar(KeyboardState(text = "abc", cursorPos = 3), "d")
        assertEquals("abcd", ks.text)
        assertEquals(4, ks.cursorPos)
    }

    @Test fun `insert char in middle splits the string`() {
        val ks = KeyboardController.insertChar(KeyboardState(text = "ace", cursorPos = 1), "b")
        assertEquals("abce", ks.text)
        assertEquals(2, ks.cursorPos)
    }

    @Test fun `insert at position zero prepends`() {
        val ks = KeyboardController.insertChar(KeyboardState(text = "bc", cursorPos = 0), "a")
        assertEquals("abc", ks.text)
        assertEquals(1, ks.cursorPos)
    }

    @Test fun `insert multi-char string advances cursor by one position`() {
        val ks = KeyboardController.insertChar(KeyboardState(text = "ac", cursorPos = 1), "xy")
        assertEquals("axyc", ks.text)
        assertEquals(2, ks.cursorPos)
    }

    @Test fun `currentName and cursorPos read through KeyboardHost`() {
        val s = DialogState.RenameInput(target = RenameTarget.GameListItem, keyboard = KeyboardState(text = "xyz", cursorPos = 3))
        val host = s as KeyboardHost
        assertEquals("xyz", host.currentName)
        assertEquals(3, host.cursorPos)
    }

}
