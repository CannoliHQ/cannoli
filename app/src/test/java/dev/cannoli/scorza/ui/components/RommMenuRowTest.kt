package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RommMenuRowTest {
    @Test fun `downloads row hidden when no downloads`() {
        assertEquals(listOf(RommActionRow.RETURN_TO_CANNOLI), RommActionRow.visibleRows(false))
    }

    @Test fun `downloads row shown when downloads active`() {
        assertEquals(
            listOf(RommActionRow.DOWNLOADS, RommActionRow.RETURN_TO_CANNOLI),
            RommActionRow.visibleRows(true),
        )
    }

    @Test fun `save sync errors row only when enabled and errors present`() {
        val withErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 1)
        val noErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 0)
        val disabled = RommSaveSyncRow.visibleRows(supported = true, enabled = false, syncErrors = 1)
        assertEquals(true, withErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, noErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, disabled.contains(RommSaveSyncRow.ERRORS))
    }
}
