package dev.cannoli.scorza.ui.quickmenu

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickMenuRowsTest {

    @Test fun `romm row only present when paired`() {
        val withRomm = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false)
        val withoutRomm = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false)
        assertEquals(true, withRomm.contains(QuickMenuRow.ROMM))
        assertEquals(false, withoutRomm.contains(QuickMenuRow.ROMM))
    }

    @Test fun `order is romm, kitchen, rescan, info`() {
        assertEquals(
            listOf(QuickMenuRow.ROMM, QuickMenuRow.KITCHEN, QuickMenuRow.RESCAN, QuickMenuRow.INFO),
            QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = true)
        )
    }

    @Test fun `without romm the rest still present in order`() {
        assertEquals(
            listOf(QuickMenuRow.KITCHEN, QuickMenuRow.RESCAN, QuickMenuRow.INFO),
            QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false)
        )
    }

    @Test fun `errors row only when sync enabled and errors present`() {
        val withErrors = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, saveSyncEnabled = true, syncErrors = 2)
        val noErrors = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, saveSyncEnabled = true, syncErrors = 0)
        val syncDisabled = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, saveSyncEnabled = false, syncErrors = 2)
        assertEquals(true, withErrors.contains(QuickMenuRow.ERRORS))
        assertEquals(false, noErrors.contains(QuickMenuRow.ERRORS))
        assertEquals(false, syncDisabled.contains(QuickMenuRow.ERRORS))
    }

    @Test fun `errors row follows conflicts`() {
        val rows = QuickMenuRow.visibleRows(
            rommPaired = true, kitchenRunning = false, saveSyncEnabled = true, pendingConflicts = 1, syncErrors = 1,
        )
        assertEquals(rows.indexOf(QuickMenuRow.CONFLICTS) + 1, rows.indexOf(QuickMenuRow.ERRORS))
    }
}
