package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RommMenuRowTest {
    @Test fun `downloads row hidden when no downloads`() {
        assertEquals(emptyList<RommActionRow>(), RommActionRow.visibleRows(false))
    }

    @Test fun `downloads row shown when downloads active`() {
        assertEquals(listOf(RommActionRow.DOWNLOADS), RommActionRow.visibleRows(true))
    }

    @Test fun `save sync errors row only when enabled and errors present`() {
        val withErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 1, pendingConflicts = 0, hasBackups = false)
        val noErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 0, pendingConflicts = 0, hasBackups = false)
        val disabled = RommSaveSyncRow.visibleRows(supported = true, enabled = false, syncErrors = 1, pendingConflicts = 0, hasBackups = false)
        assertEquals(true, withErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, noErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, disabled.contains(RommSaveSyncRow.ERRORS))
    }

    @Test fun `restore row appears whenever backups exist even if sync is off`() {
        val withBackups = RommSaveSyncRow.visibleRows(supported = true, enabled = false, hasBackups = true, pendingConflicts = 0, syncErrors = 0)
        val noBackups = RommSaveSyncRow.visibleRows(supported = true, enabled = true, hasBackups = false, pendingConflicts = 0, syncErrors = 0)
        assertEquals(true, withBackups.contains(RommSaveSyncRow.RESTORE))
        assertEquals(false, noBackups.contains(RommSaveSyncRow.RESTORE))
    }

    /**
     * Restore is appended last, so a caller that omitted it happened to look up the right index.
     * The menu builder owns the row list now, but pin the ordering the shortcut depended on.
     */
    @Test fun `restore sits after every gated row`() {
        val all = RommSaveSyncRow.visibleRows(
            supported = true, enabled = true, pendingConflicts = 1, syncErrors = 1, hasBackups = true,
        )
        assertEquals(RommSaveSyncRow.RESTORE, all.last())
        assertEquals(
            listOf(
                RommSaveSyncRow.TOGGLE,
                RommSaveSyncRow.BACKUPS,
                RommSaveSyncRow.HISTORY,
                RommSaveSyncRow.CONFLICTS,
                RommSaveSyncRow.ERRORS,
                RommSaveSyncRow.RESTORE,
            ),
            all,
        )
    }
}
