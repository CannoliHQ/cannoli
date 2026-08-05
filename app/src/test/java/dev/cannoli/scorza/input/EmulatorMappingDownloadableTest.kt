package dev.cannoli.scorza.input

import dev.cannoli.scorza.config.EmulatorSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMappingDownloadableTest {
    private val ownPackage = "dev.cannoli.scorza"
    private val stock = "com.retroarch.aarch64"

    @Test fun `not-installed RetroArch core on the embedded RetroArch is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = false, raPackage = ownPackage, packageName = ownPackage))
    }

    @Test fun `not-installed RetroArch core with an unset package is downloadable`() {
        assertTrue(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = false, raPackage = "", packageName = ownPackage))
    }

    @Test fun `installed RetroArch core is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = true, raPackage = ownPackage, packageName = ownPackage))
    }

    @Test fun `not-installed core on stock RetroArch is not downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.RetroArch, available = false, raPackage = stock, packageName = ownPackage))
    }

    @Test fun `Internal and Standalone sources are never downloadable`() {
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Internal, available = false, raPackage = ownPackage, packageName = ownPackage))
        assertFalse(EmulatorMappingBuilder.isDownloadable(EmulatorSource.Standalone, available = false, raPackage = ownPackage, packageName = ownPackage))
    }
}
