package dev.cannoli.scorza.romm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommCapabilitiesTest {

    @Test fun `5_0_0 supported`() {
        assertTrue(RommCapabilities.isSupported("5.0.0"))
    }

    @Test fun `5_0_1 supported`() {
        assertTrue(RommCapabilities.isSupported("5.0.1"))
    }

    @Test fun `5_1_0 supported`() {
        assertTrue(RommCapabilities.isSupported("5.1.0"))
    }

    @Test fun `6_0_0 supported`() {
        assertTrue(RommCapabilities.isSupported("6.0.0"))
    }

    @Test fun `4_9_0 not supported`() {
        assertFalse(RommCapabilities.isSupported("4.9.0"))
    }

    @Test fun `4_10_0 not supported`() {
        assertFalse(RommCapabilities.isSupported("4.10.0"))
    }

    @Test fun `prerelease of 5_0_0 not supported`() {
        assertFalse(RommCapabilities.isSupported("5.0.0-beta.1"))
    }

    @Test fun `prerelease suffix without dash not supported`() {
        assertFalse(RommCapabilities.isSupported("5.0.0b1"))
    }

    @Test fun `prerelease of later patch supported`() {
        assertTrue(RommCapabilities.isSupported("5.0.1-beta.1"))
    }

    @Test fun `prerelease of later minor supported`() {
        assertTrue(RommCapabilities.isSupported("5.1.0-rc.1"))
    }

    @Test fun `build metadata ignored`() {
        assertTrue(RommCapabilities.isSupported("5.0.0+build.7"))
    }

    @Test fun `v prefix accepted`() {
        assertTrue(RommCapabilities.isSupported("v5.0.0"))
    }

    @Test fun `null is neither supported nor known unsupported`() {
        assertFalse(RommCapabilities.isSupported(null))
        assertFalse(RommCapabilities.isKnownUnsupported(null))
    }

    @Test fun `empty is neither supported nor known unsupported`() {
        assertFalse(RommCapabilities.isSupported(""))
        assertFalse(RommCapabilities.isKnownUnsupported(""))
    }

    @Test fun `garbage is neither supported nor known unsupported`() {
        assertFalse(RommCapabilities.isSupported("latest"))
        assertFalse(RommCapabilities.isKnownUnsupported("latest"))
    }

    @Test fun `two segment version is neither supported nor known unsupported`() {
        assertFalse(RommCapabilities.isSupported("5.0"))
        assertFalse(RommCapabilities.isKnownUnsupported("5.0"))
    }

    @Test fun `old versions are known unsupported`() {
        assertTrue(RommCapabilities.isKnownUnsupported("4.9.0"))
        assertTrue(RommCapabilities.isKnownUnsupported("3.0.0"))
    }

    @Test fun `supported version is not known unsupported`() {
        assertFalse(RommCapabilities.isKnownUnsupported("5.0.0"))
    }
}
