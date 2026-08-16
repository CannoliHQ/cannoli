package dev.cannoli.scorza.onboarding

import dev.cannoli.scorza.boot.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPermissionTest {

    private class FakeStatus(val storage: Boolean, val overlay: Boolean) : PermissionStatus {
        override fun hasStorage() = storage
        override fun hasOverlay() = overlay
    }

    @Test fun singleScreenDeviceIsNeverOfferedTheOverlayPermission() {
        assertEquals(listOf(OnboardingPermission.STORAGE), onboardingPermissions(hasSecondDisplay = false))
    }

    @Test fun dualScreenDeviceIsOfferedTheOverlayPermission() {
        assertEquals(
            listOf(OnboardingPermission.STORAGE, OnboardingPermission.OVERLAY),
            onboardingPermissions(hasSecondDisplay = true),
        )
    }

    @Test fun onlyStorageIsRequired() {
        assertTrue(OnboardingPermission.STORAGE.required)
        assertFalse(OnboardingPermission.OVERLAY.required)
    }

    @Test fun grantsAreReadPerPermission() {
        val permissions = onboardingPermissions(hasSecondDisplay = true)
        assertEquals(
            emptySet<OnboardingPermission>(),
            grantedOnboardingPermissions(permissions, FakeStatus(storage = false, overlay = false)),
        )
        assertEquals(
            setOf(OnboardingPermission.OVERLAY),
            grantedOnboardingPermissions(permissions, FakeStatus(storage = false, overlay = true)),
        )
        assertEquals(
            permissions.toSet(),
            grantedOnboardingPermissions(permissions, FakeStatus(storage = true, overlay = true)),
        )
    }

    @Test fun grantsNeverReportAPermissionThatWasNotOffered() {
        val permissions = onboardingPermissions(hasSecondDisplay = false)
        assertEquals(
            setOf(OnboardingPermission.STORAGE),
            grantedOnboardingPermissions(permissions, FakeStatus(storage = true, overlay = true)),
        )
    }
}
