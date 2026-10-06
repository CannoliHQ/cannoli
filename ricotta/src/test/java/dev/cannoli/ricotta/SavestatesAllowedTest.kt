package dev.cannoli.ricotta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavestatesAllowedTest {

    @Test fun `an effective softcore launch keeps the save state rows`() {
        assertTrue(EmbeddedRetroArchBridge.savestatesAllowedFor(hardcoreInEffect = false))
    }

    @Test fun `an effective hardcore launch hides the save state rows`() {
        assertFalse(EmbeddedRetroArchBridge.savestatesAllowedFor(hardcoreInEffect = true))
    }
}
