package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.components.KeyboardState
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHostTest {

    private val dialogs: List<DialogState> = listOf(
        DialogState.RenameInput(gameName = "g"),
        DialogState.NewCollectionInput(),
        DialogState.CollectionRenameInput(collectionId = 1, oldDisplayName = "x"),
        DialogState.NewFolderInput(parentPath = "/p"),
    )

    @Test fun `withKeyboard round-trips and currentName reads through`() {
        dialogs.forEach { d ->
            val host = d as KeyboardHost
            val updated = host.withKeyboard(KeyboardState(text = "abc", cursorPos = 2)) as KeyboardHost
            assertEquals("abc", updated.currentName)
            assertEquals(2, updated.cursorPos)
        }
    }
}
