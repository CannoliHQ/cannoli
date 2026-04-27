package dev.cannoli.scorza.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ---- withBackspace ----

    @Test fun `backspace removes the character before the cursor`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "hello", cursorPos = 5)
        val after = state.withBackspace() as DialogState.RenameInput
        assertEquals("hell", after.currentName)
        assertEquals(4, after.cursorPos)
    }

    @Test fun `backspace from middle of string removes correct character`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "abcdef", cursorPos = 3)
        val after = state.withBackspace() as DialogState.RenameInput
        assertEquals("abdef", after.currentName)
        assertEquals(2, after.cursorPos)
    }

    @Test fun `backspace at position zero returns null`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "abc", cursorPos = 0)
        assertNull(state.withBackspace())
    }

    @Test fun `backspace on non-keyboard state returns null`() {
        assertNull(DialogState.None.withBackspace())
        assertNull(DialogState.QuitConfirm.withBackspace())
    }

    // ---- withInsertedChar ----

    @Test fun `insert char at end appends to name`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "abc", cursorPos = 3)
        val after = state.withInsertedChar("d") as DialogState.RenameInput
        assertEquals("abcd", after.currentName)
        assertEquals(4, after.cursorPos)
    }

    @Test fun `insert char in middle splits the string`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "ace", cursorPos = 1)
        val after = state.withInsertedChar("b") as DialogState.RenameInput
        assertEquals("abce", after.currentName)
        assertEquals(2, after.cursorPos)
    }

    @Test fun `insert at position zero prepends`() {
        val state = DialogState.RenameInput(gameName = "g", currentName = "bc", cursorPos = 0)
        val after = state.withInsertedChar("a") as DialogState.RenameInput
        assertEquals("abc", after.currentName)
        assertEquals(1, after.cursorPos)
    }

    @Test fun `insert multi-char string advances cursor by one position`() {
        // The function increments cursor by 1 regardless of inserted length;
        // documenting that behavior so callers know it expects single grapheme inputs.
        val state = DialogState.RenameInput(gameName = "g", currentName = "ac", cursorPos = 1)
        val after = state.withInsertedChar("xy") as DialogState.RenameInput
        assertEquals("axyc", after.currentName)
        assertEquals(2, after.cursorPos)
    }

    @Test fun `insert on non-keyboard state returns null`() {
        assertNull(DialogState.None.withInsertedChar("a"))
    }

    // ---- withCursor / withCaps / withSymbols / withKeyboard / withNameAndCursor ----

    @Test fun `withCursor updates cursor for each keyboard state subtype`() {
        val rename = DialogState.RenameInput(gameName = "g", currentName = "abc")
        assertEquals(2, (rename.withCursor(2) as DialogState.RenameInput).cursorPos)

        val newCol = DialogState.NewCollectionInput(currentName = "abc")
        assertEquals(2, (newCol.withCursor(2) as DialogState.NewCollectionInput).cursorPos)

        val collRename = DialogState.CollectionRenameInput(oldStem = "old", currentName = "abc")
        assertEquals(2, (collRename.withCursor(2) as DialogState.CollectionRenameInput).cursorPos)

        val profile = DialogState.ProfileNameInput(isNew = true, currentName = "abc")
        assertEquals(2, (profile.withCursor(2) as DialogState.ProfileNameInput).cursorPos)

        val newFolder = DialogState.NewFolderInput(parentPath = "/", currentName = "abc")
        assertEquals(2, (newFolder.withCursor(2) as DialogState.NewFolderInput).cursorPos)
    }

    @Test fun `withCursor on non-keyboard state returns the receiver unchanged`() {
        val s = DialogState.QuitConfirm
        assertSame(s, s.withCursor(5))
    }

    @Test fun `withCaps and withSymbols toggle on keyboard states`() {
        val s = DialogState.RenameInput(gameName = "g", currentName = "abc", caps = false, symbols = false)
        val capsOn = s.withCaps(true) as DialogState.RenameInput
        assertTrue(capsOn.caps)
        val symbolsOn = s.withSymbols(true) as DialogState.RenameInput
        assertTrue(symbolsOn.symbols)
    }

    @Test fun `withKeyboard records row and column`() {
        val s = DialogState.RenameInput(gameName = "g", currentName = "abc")
        val moved = s.withKeyboard(3, 7) as DialogState.RenameInput
        assertEquals(3, moved.keyRow)
        assertEquals(7, moved.keyCol)
    }

    @Test fun `withNameAndCursor updates both fields atomically`() {
        val s = DialogState.RenameInput(gameName = "g", currentName = "abc", cursorPos = 1)
        val updated = s.withNameAndCursor("xyz", 3) as DialogState.RenameInput
        assertEquals("xyz", updated.currentName)
        assertEquals(3, updated.cursorPos)
    }

    // ---- asKeyboardState ----

    @Test fun `asKeyboardState returns non-null only for input states`() {
        assertNotNull(DialogState.RenameInput(gameName = "g", currentName = "").asKeyboardState())
        assertNotNull(DialogState.NewCollectionInput().asKeyboardState())
        assertNull(DialogState.None.asKeyboardState())
        assertNull(DialogState.QuitConfirm.asKeyboardState())
        assertNull(DialogState.ContextMenu(gameName = "g", options = listOf("a")).asKeyboardState())
    }

    // ---- isFullScreen ----

    @Test fun `isFullScreen is true for context menus and input states`() {
        assertTrue(DialogState.ContextMenu(gameName = "g", options = listOf("a")).isFullScreen)
        assertTrue(DialogState.BulkContextMenu(gamePaths = listOf("/x"), options = listOf("a")).isFullScreen)
        assertTrue(DialogState.RenameInput(gameName = "g", currentName = "").isFullScreen)
        assertTrue(DialogState.About().isFullScreen)
    }
}
