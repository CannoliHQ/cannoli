package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.components.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogStateTest {

    // ---- withMenuDelta ----

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

    // ---- backspace via KeyboardController ----

    @Test fun `backspace removes the character before the cursor`() {
        val state = DialogState.RenameInput(gameName = "g", keyboard = KeyboardState(text = "hello", cursorPos = 5))
        val ks = KeyboardController.backspace(state.keyboard)
        assertEquals("hell", ks.text)
        assertEquals(4, ks.cursorPos)
    }

    @Test fun `backspace from middle of string removes correct character`() {
        val state = DialogState.RenameInput(gameName = "g", keyboard = KeyboardState(text = "abcdef", cursorPos = 3))
        val ks = KeyboardController.backspace(state.keyboard)
        assertEquals("abdef", ks.text)
        assertEquals(2, ks.cursorPos)
    }

    @Test fun `backspace at position zero is a no-op`() {
        val ks0 = KeyboardState(text = "abc", cursorPos = 0)
        assertEquals(ks0, KeyboardController.backspace(ks0))
    }

    // ---- insertChar via KeyboardController ----

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

    // ---- withKeyboard / cursorPos / caps / symbols via KeyboardHost ----

    @Test fun `withKeyboard updates keyboard state for each subtype`() {
        val rename = DialogState.RenameInput(gameName = "g")
        val updated = rename.withKeyboard(KeyboardState(text = "abc", cursorPos = 2)) as DialogState.RenameInput
        assertEquals(2, updated.keyboard.cursorPos)

        val newCol = DialogState.NewCollectionInput()
        val updatedCol = newCol.withKeyboard(KeyboardState(text = "abc", cursorPos = 2)) as DialogState.NewCollectionInput
        assertEquals(2, updatedCol.keyboard.cursorPos)

        val collRename = DialogState.CollectionRenameInput(collectionId = 1L, oldDisplayName = "old")
        val updatedCR = collRename.withKeyboard(KeyboardState(text = "abc", cursorPos = 2)) as DialogState.CollectionRenameInput
        assertEquals(2, updatedCR.keyboard.cursorPos)

        val newFolder = DialogState.NewFolderInput(parentPath = "/")
        val updatedNF = newFolder.withKeyboard(KeyboardState(text = "abc", cursorPos = 2)) as DialogState.NewFolderInput
        assertEquals(2, updatedNF.keyboard.cursorPos)
    }

    @Test fun `withKeyboard on non-keyboard state - QuitConfirm is not a KeyboardHost`() {
        assertSame(null, DialogState.QuitConfirm as? KeyboardHost)
    }

    @Test fun `caps and symbols accessible via keyboard`() {
        val s = DialogState.RenameInput(gameName = "g", keyboard = KeyboardState(caps = false, symbols = false))
        val capsOn = s.withKeyboard(s.keyboard.copy(caps = true)) as DialogState.RenameInput
        assertTrue(capsOn.keyboard.caps)
        val symbolsOn = s.withKeyboard(s.keyboard.copy(symbols = true)) as DialogState.RenameInput
        assertTrue(symbolsOn.keyboard.symbols)
    }

    @Test fun `withKeyboard records row and column`() {
        val s = DialogState.RenameInput(gameName = "g")
        val moved = s.withKeyboard(s.keyboard.copy(keyRow = 3, keyCol = 7)) as DialogState.RenameInput
        assertEquals(3, moved.keyboard.keyRow)
        assertEquals(7, moved.keyboard.keyCol)
    }

    @Test fun `currentName and cursorPos read through KeyboardHost`() {
        val s = DialogState.RenameInput(gameName = "g", keyboard = KeyboardState(text = "xyz", cursorPos = 3))
        val host = s as KeyboardHost
        assertEquals("xyz", host.currentName)
        assertEquals(3, host.cursorPos)
    }

    // ---- KeyboardHost membership ----

    @Test fun `keyboard dialogs are KeyboardHost, non-keyboard dialogs are not`() {
        assertTrue(DialogState.RenameInput(gameName = "g") is KeyboardHost)
        assertTrue(DialogState.NewCollectionInput() is KeyboardHost)
        assertTrue(DialogState.None !is KeyboardHost)
        assertTrue(DialogState.QuitConfirm !is KeyboardHost)
        assertTrue(DialogState.ContextMenu(gameName = "g", options = listOf("a")) !is KeyboardHost)
    }

    // ---- isFullScreen ----

    @Test fun `isFullScreen is true for context menus and input states`() {
        assertTrue(DialogState.ContextMenu(gameName = "g", options = listOf("a")).isFullScreen)
        assertTrue(DialogState.BulkContextMenu(gamePaths = listOf("/x"), options = listOf("a")).isFullScreen)
        assertTrue(DialogState.RenameInput(gameName = "g").isFullScreen)
        assertTrue(DialogState.About().isFullScreen)
    }
}
