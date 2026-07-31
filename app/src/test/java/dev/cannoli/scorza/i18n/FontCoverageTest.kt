package dev.cannoli.scorza.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCoverageTest {

    @Test fun `no prompt when there is no coverage sample`() {
        assertFalse(fontPromptNeeded(null, false))
    }

    @Test fun `no prompt when the font covers the sample`() {
        assertFalse(fontPromptNeeded("あ", true))
    }

    @Test fun `prompt when the font lacks coverage`() {
        assertTrue(fontPromptNeeded("あ", false))
    }
}
