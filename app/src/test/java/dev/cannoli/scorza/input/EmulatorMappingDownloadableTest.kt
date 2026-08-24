package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMappingDownloadableTest {

    @Test fun `a missing core on the embedded RetroArch is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Embedded, CoreAvailability.UNAVAILABLE))
    }

    @Test fun `an installed core on the embedded RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Embedded, CoreAvailability.AVAILABLE))
    }

    // A standalone app is installed from its own store listing, not fetched by Cannoli, so no
    // download affordance is offered however the availability check came out.
    @Test fun `Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, CoreAvailability.UNAVAILABLE))
    }

}
