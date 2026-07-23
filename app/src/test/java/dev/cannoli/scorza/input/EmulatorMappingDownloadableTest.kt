package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMappingDownloadableTest {
    private val ricotta = "dev.cannoli.ricotta.aarch64"
    private val stock = "com.retroarch.aarch64"

    @Test fun `not-installed RetroArch core on Ricotta is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = false, raPackage = ricotta))
    }

    @Test fun `installed RetroArch core is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = true, raPackage = ricotta))
    }

    @Test fun `not-installed core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = false, raPackage = stock))
    }

    @Test fun `Internal and Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Internal, available = false, raPackage = ricotta))
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, available = false, raPackage = ricotta))
    }
}
