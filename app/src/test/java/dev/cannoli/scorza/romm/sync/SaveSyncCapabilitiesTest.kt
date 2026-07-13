package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncCapabilitiesTest {

    @Test fun supported_when_exactly_4_9_0() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("4.9.0"))
    }

    @Test fun supported_when_patch_higher() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("4.9.1"))
    }

    @Test fun supported_when_minor_higher() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("4.10.0"))
    }

    @Test fun supported_when_major_higher() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("5.0.0"))
    }

    @Test fun not_supported_when_below_4_9_0() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("4.8.9"))
    }

    @Test fun not_supported_when_much_older() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("3.0.0"))
    }

    @Test fun not_supported_when_null() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync(null))
    }

    @Test fun not_supported_when_blank() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync(""))
    }

    @Test fun not_supported_when_non_semver() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("latest"))
    }

    @Test fun not_supported_when_prerelease_below() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("4.9.0-beta.1"))
    }

    @Test fun not_supported_when_unknown() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("unknown"))
    }

    @Test fun supported_when_prerelease_of_higher_major() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("5.0.0-beta.1"))
    }

    @Test fun supported_when_prerelease_of_higher_minor() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("4.10.0-rc.1"))
    }

    @Test fun supported_when_prerelease_of_higher_patch() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("4.9.1-beta.1"))
    }

    @Test fun supported_when_pep440_prerelease_of_higher_major() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("5.0.0b1"))
    }

    @Test fun supported_when_build_metadata() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("5.0.0+build.7"))
    }

    @Test fun supported_when_v_prefixed() {
        assertTrue(SaveSyncCapabilities.supportsSaveSync("v5.0.0"))
    }

    @Test fun not_supported_when_prerelease_of_older_release() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("4.8.0-beta.1"))
    }

    @Test fun not_supported_when_pep440_prerelease_of_floor() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("4.9.0b1"))
    }

    @Test fun not_supported_when_partial_version() {
        assertFalse(SaveSyncCapabilities.supportsSaveSync("5.0"))
    }
}
