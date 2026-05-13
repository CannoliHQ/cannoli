package dev.cannoli.scorza.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPermissionsTest {

    private fun screen(
        permissions: List<OnboardingPermission> = listOf(OnboardingPermission.STORAGE, OnboardingPermission.BLUETOOTH),
        granted: Set<OnboardingPermission> = emptySet(),
        selectedIndex: Int = 0,
    ) = LauncherScreen.OnboardingPermissions(permissions, granted, selectedIndex)

    @Test fun movedDownAdvancesFocusThenClampsAtLastIndex() {
        assertEquals(1, screen(selectedIndex = 0).moved(1).selectedIndex)
        assertEquals(1, screen(selectedIndex = 1).moved(1).selectedIndex)
    }

    @Test fun movedUpClampsAtZero() {
        assertEquals(0, screen(selectedIndex = 1).moved(-1).selectedIndex)
        assertEquals(0, screen(selectedIndex = 0).moved(-1).selectedIndex)
    }

    @Test fun movedNeverLeavesRangeWithSingleCard() {
        val single = screen(permissions = listOf(OnboardingPermission.STORAGE))
        assertEquals(0, single.moved(1).selectedIndex)
        assertEquals(0, single.moved(-1).selectedIndex)
    }

    @Test fun focusedPermissionFollowsSelectedIndex() {
        assertEquals(OnboardingPermission.STORAGE, screen(selectedIndex = 0).focusedPermission)
        assertEquals(OnboardingPermission.BLUETOOTH, screen(selectedIndex = 1).focusedPermission)
    }

    @Test fun isFocusedGrantedReflectsGrantedSet() {
        assertFalse(screen(selectedIndex = 0, granted = setOf(OnboardingPermission.BLUETOOTH)).isFocusedGranted)
        assertTrue(screen(selectedIndex = 0, granted = setOf(OnboardingPermission.STORAGE)).isFocusedGranted)
    }

    @Test fun allGrantedRequiresEveryListedPermission() {
        assertFalse(screen(granted = setOf(OnboardingPermission.STORAGE)).allGranted)
        assertTrue(screen(granted = setOf(OnboardingPermission.STORAGE, OnboardingPermission.BLUETOOTH)).allGranted)
        assertTrue(screen(permissions = listOf(OnboardingPermission.STORAGE), granted = setOf(OnboardingPermission.STORAGE)).allGranted)
    }
}
