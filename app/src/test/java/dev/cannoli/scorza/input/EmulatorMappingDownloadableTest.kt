package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMappingDownloadableTest {
    private val ricotta = "dev.cannoli.ricotta.aarch64"
    private val stock = "com.retroarch.aarch64"

    @Test fun `not-installed RetroArch core on Ricotta is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, availability = CoreAvailability.UNAVAILABLE, raPackage = ricotta))
    }

    @Test fun `installed RetroArch core is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, availability = CoreAvailability.AVAILABLE, raPackage = ricotta))
    }

    @Test fun `not-installed core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, availability = CoreAvailability.UNAVAILABLE, raPackage = stock))
    }

    @Test fun `Internal and Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Internal, availability = CoreAvailability.UNAVAILABLE, raPackage = ricotta))
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, availability = CoreAvailability.UNAVAILABLE, raPackage = ricotta))
    }

    @Test fun `an unknown core on Ricotta is downloadable`() {
        // SILENT RicottaArch reports UNKNOWN for every candidate core. Withholding the download
        // here is what stranded a freshly installed RicottaArch that has never been launched.
        assertTrue(EmulatorMappingBuilder.isDownloadable(
            EmulatorSource.RetroArch, CoreAvailability.UNKNOWN, ricotta,
        ))
    }

    @Test fun `an unknown core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(
            EmulatorSource.RetroArch, CoreAvailability.UNKNOWN, stock,
        ))
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

    @Test fun `Internal and Standalone rows are unavailable regardless of RetroArch reporting`() {
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.Internal, raCannotReport = true),
        )
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.Standalone, raCannotReport = true),
        )
    }

    @Test fun `an unknown current row on Ricotta is downloadable`() {
        val availability = EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.RetroArch, raCannotReport = true)
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, availability, ricotta))
    }
}
