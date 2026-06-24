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

    @Test fun `settings rows in declared order`() {
        assertEquals(
            listOf(
                RommSettingsRow.COVER_ART, RommSettingsRow.CONCURRENT, RommSettingsRow.PLATFORMS,
                RommSettingsRow.COLLECTIONS, RommSettingsRow.ADVANCED, RommSettingsRow.SERVER_INFO,
            ),
            RommSettingsRow.entries.toList(),
        )
    }
}
