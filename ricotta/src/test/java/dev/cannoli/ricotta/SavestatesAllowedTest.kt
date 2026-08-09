package dev.cannoli.ricotta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save state rows are gated on the launcher's effective-hardcore decision carried across the
 * launch parcel, never on the live cheevos settings a stale per-game override can clobber. These
 * pin the polarity, which is the exact thing the override-clobber bug inverted: an effective
 * softcore launch must keep its rows.
 */
class SavestatesAllowedTest {

    @Test fun `an effective softcore launch keeps the save state rows`() {
        assertTrue(EmbeddedRetroArchBridge.savestatesAllowedFor(hardcoreInEffect = false))
    }

    @Test fun `an effective hardcore launch hides the save state rows`() {
        assertFalse(EmbeddedRetroArchBridge.savestatesAllowedFor(hardcoreInEffect = true))
    }
}
