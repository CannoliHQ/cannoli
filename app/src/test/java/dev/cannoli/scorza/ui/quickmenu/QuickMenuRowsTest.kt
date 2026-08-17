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

    @Test fun `order is settings, romm, kitchen, rescan, info, about`() {
        assertEquals(
            listOf(
                QuickMenuRow.SETTINGS, QuickMenuRow.ROMM, QuickMenuRow.KITCHEN,
                QuickMenuRow.RESCAN, QuickMenuRow.INFO, QuickMenuRow.ABOUT,
            ),
            QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = true)
        )
    }

    @Test fun `without romm the rest still present in order`() {
        assertEquals(
            listOf(
                QuickMenuRow.SETTINGS, QuickMenuRow.KITCHEN, QuickMenuRow.RESCAN,
                QuickMenuRow.INFO, QuickMenuRow.ABOUT,
            ),
            QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false)
        )
    }

    @Test fun `about follows info`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, debugBuild = true)
        assertEquals(rows.indexOf(QuickMenuRow.INFO) + 1, rows.indexOf(QuickMenuRow.ABOUT))
    }

    @Test fun `debug row only on debug builds and always last`() {
        val debug = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, debugBuild = true)
        val release = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, debugBuild = false)
        assertEquals(true, debug.contains(QuickMenuRow.DEBUG))
        assertEquals(false, release.contains(QuickMenuRow.DEBUG))
        assertEquals(QuickMenuRow.DEBUG, debug.last())
        assertEquals(QuickMenuRow.ABOUT, release.last())
    }

    @Test fun `settings row is first when there is nothing to attend to`() {
        val paired = QuickMenuRow.visibleRows(
            rommPaired = true, kitchenRunning = true, saveSyncEnabled = true,
            pendingConflicts = 0, syncErrors = 0, downloadCount = 4,
        )
        val bare = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false)
        assertEquals(QuickMenuRow.SETTINGS, paired.first())
        assertEquals(QuickMenuRow.SETTINGS, bare.first())
    }

    @Test fun `conflicts then errors lead the menu`() {
        val rows = QuickMenuRow.visibleRows(
            rommPaired = true, kitchenRunning = true, saveSyncEnabled = true,
            pendingConflicts = 2, syncErrors = 1, downloadCount = 4,
        )
        assertEquals(
            listOf(QuickMenuRow.CONFLICTS, QuickMenuRow.ERRORS, QuickMenuRow.SETTINGS),
            rows.take(3),
        )
    }

    @Test fun `errors row is first when only errors are present`() {
        val rows = QuickMenuRow.visibleRows(
            rommPaired = true, kitchenRunning = true, saveSyncEnabled = true,
            pendingConflicts = 0, syncErrors = 1, downloadCount = 4,
        )
        assertEquals(QuickMenuRow.ERRORS, rows.first())
        assertEquals(QuickMenuRow.SETTINGS, rows[1])
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

    @Test fun `downloads row present immediately after romm when paired`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, downloadCount = 3)
        assertEquals(true, rows.contains(QuickMenuRow.DOWNLOADS))
        assertEquals(rows.indexOf(QuickMenuRow.ROMM) + 1, rows.indexOf(QuickMenuRow.DOWNLOADS))
    }

    @Test fun `downloads row follows settings when not paired`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = false, kitchenRunning = false, downloadCount = 3)
        assertEquals(true, rows.contains(QuickMenuRow.DOWNLOADS))
        assertEquals(false, rows.contains(QuickMenuRow.ROMM))
        assertEquals(rows.indexOf(QuickMenuRow.SETTINGS) + 1, rows.indexOf(QuickMenuRow.DOWNLOADS))
    }

    @Test fun `downloads row absent when queue empty`() {
        val rows = QuickMenuRow.visibleRows(rommPaired = true, kitchenRunning = false, downloadCount = 0)
        assertEquals(false, rows.contains(QuickMenuRow.DOWNLOADS))
    }
}
