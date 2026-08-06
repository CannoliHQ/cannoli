package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
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

    // Cores for a separately installed RetroArch are the user's own to manage, so Cannoli offers
    // no download affordance for them however the availability check came out.
    @Test fun `no core on an external RetroArch is downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.UNAVAILABLE))
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.UNKNOWN))
    }

    @Test fun `Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, CoreAvailability.UNAVAILABLE))
    }

    // The embedded runner reads a directory, so an unknown core still means "not there yet" and
    // must stay downloadable. Withholding the download is what stranded a fresh install.
    @Test fun `an unknown core on the embedded RetroArch is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Embedded, CoreAvailability.UNKNOWN))
    }

    @Test fun `a RetroArch row is unknown when RetroArch cannot report`() {
        assertEquals(
            CoreAvailability.UNKNOWN,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.RetroArch, raCannotReport = true),
        )
    }

    @Test fun `a RetroArch row is unavailable when RetroArch can report`() {
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.RetroArch, raCannotReport = false),
        )
    }

    // The embedded runner always knows: its cores are a directory listing, not a query, so it has
    // no unknown state even while an external RetroArch is failing to report.
    @Test fun `an Embedded row is unavailable regardless of RetroArch reporting`() {
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.Embedded, raCannotReport = true),
        )
    }

    @Test fun `Standalone rows are unavailable regardless of RetroArch reporting`() {
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.Standalone, raCannotReport = true),
        )
    }

    @Test fun `an unknown current row on the embedded RetroArch is downloadable`() {
        val availability = EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.Embedded, raCannotReport = true)
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Embedded, availability))
    }
}
