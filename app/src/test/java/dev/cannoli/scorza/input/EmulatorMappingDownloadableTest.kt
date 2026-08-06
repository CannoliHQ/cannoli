package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMappingDownloadableTest {
    private val ownPackage = "dev.cannoli.scorza"
    private val stock = "com.retroarch.aarch64"

    @Test fun `not-installed RetroArch core on the embedded RetroArch is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.UNAVAILABLE, raPackage = ownPackage, packageName = ownPackage))
    }

    @Test fun `not-installed RetroArch core with an unset package is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.UNAVAILABLE, raPackage = "", packageName = ownPackage))
    }

    @Test fun `installed RetroArch core is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.AVAILABLE, raPackage = ownPackage, packageName = ownPackage))
    }

    @Test fun `not-installed core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, CoreAvailability.UNAVAILABLE, raPackage = stock, packageName = ownPackage))
    }

    @Test fun `Internal and Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Internal, CoreAvailability.UNAVAILABLE, raPackage = ownPackage, packageName = ownPackage))
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, CoreAvailability.UNAVAILABLE, raPackage = ownPackage, packageName = ownPackage))
    }

    @Test fun `an unknown core on the embedded RetroArch is downloadable`() {
        // A RetroArch that cannot report reports UNKNOWN for every candidate core. Withholding
        // the download here is what stranded an install that has never been launched.
        assertTrue(EmulatorMappingBuilder.isDownloadable(
            EmulatorSource.RetroArch, CoreAvailability.UNKNOWN, ownPackage, ownPackage,
        ))
    }

    @Test fun `an unknown core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(
            EmulatorSource.RetroArch, CoreAvailability.UNKNOWN, stock, ownPackage,
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

    @Test fun `an unknown current row on the embedded RetroArch is downloadable`() {
        val availability = EmulatorMappingBuilder.currentRowAvailability(EmulatorSource.RetroArch, raCannotReport = true)
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, availability, ownPackage, ownPackage))
    }
}
