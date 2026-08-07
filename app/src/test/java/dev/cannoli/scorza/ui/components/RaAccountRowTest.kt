package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.ui.screens.DialogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaAccountRowTest {

    @Test fun `the rows are in the designed order`() {
        assertEquals(
            listOf(
                RaAccountRow.ACCOUNT,
                RaAccountRow.HARDCORE,
                RaAccountRow.OFFLINE_SETS,
                RaAccountRow.LOG_OUT,
            ),
            RaAccountRow.entries.toList(),
        )
    }

    @Test fun `only hardcore cycles on left and right`() {
        assertTrue(RaAccountRow.HARDCORE.isCycle)
        assertTrue(RaAccountRow.entries.filter { it.isCycle } == listOf(RaAccountRow.HARDCORE))
    }

    @Test fun `the account dialog navigates as a list`() {
        val ds: DialogState.RAAccount = DialogState.RAAccount(username = "bob")
        assertEquals(0, ds.selectedIndex)
        assertEquals(2, (ds.withSelectedIndex(2) as DialogState.RAAccount).selectedIndex)
    }
}
