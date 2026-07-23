package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.R
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

    @Test fun `create and rename dialogs carry header titles by type`() {
        assertEquals(R.string.keyboard_title_new_collection, DialogState.NewCollectionInput().titleRes)
        assertEquals(R.string.keyboard_title_rename_collection, DialogState.CollectionRenameInput(collectionId = 1, oldDisplayName = "x").titleRes)
        assertEquals(R.string.keyboard_title_new_folder, DialogState.NewFolderInput(parentPath = "/p").titleRes)
    }

    @Test fun `RenameInput title defaults to null and honors an explicit titleRes`() {
        assertEquals(null, DialogState.RenameInput(gameName = "g").titleRes)
        assertEquals(
            R.string.keyboard_title_rename_game,
            DialogState.RenameInput(gameName = "g", titleRes = R.string.keyboard_title_rename_game).titleRes,
        )
    }
}
