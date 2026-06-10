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
}
