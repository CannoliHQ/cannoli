package dev.cannoli.scorza.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Test

class MappingNoticeTest {
    @Test fun `the notice is never focusable`() {
        assertFalse(MappingItem.Notice("This RetroArch version cannot report installed cores").isSelectable)
    }
}
