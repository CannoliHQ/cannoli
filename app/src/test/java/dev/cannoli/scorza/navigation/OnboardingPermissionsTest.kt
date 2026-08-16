package dev.cannoli.scorza.navigation

import dev.cannoli.scorza.onboarding.OnboardingPermission
import dev.cannoli.scorza.onboarding.OnboardingPermissionsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPermissionsTest {

    private val storage = OnboardingPermission.STORAGE
    private val overlay = OnboardingPermission.OVERLAY
    private val singleScreen = listOf(storage)
    private val dualScreen = listOf(storage, overlay)

    private fun screen(
        permissions: List<OnboardingPermission> = singleScreen,
        granted: Set<OnboardingPermission> = emptySet(),
        selectedIndex: Int = 0,
    ) = LauncherScreen.OnboardingPermissions(
        permissions = permissions,
        granted = granted,
        selectedIndex = selectedIndex,
    )

    @Test fun focusIsAPlainLookupIntoThePermissionList() {
        val s = screen(permissions = dualScreen)
        assertEquals(storage, s.copy(selectedIndex = 0).focusedPermission)
        assertEquals(overlay, s.copy(selectedIndex = 1).focusedPermission)
        assertNull(s.copy(selectedIndex = 2).focusedPermission)
    }

    @Test fun movementClampsToThePermissionList() {
        val s = screen(permissions = dualScreen)
        assertEquals(0, s.moved(-1).selectedIndex)
        assertEquals(1, s.moved(1).selectedIndex)
        assertEquals(1, s.copy(selectedIndex = 1).moved(1).selectedIndex)
    }

    @Test fun movementIsSafeWithNoPermissionsAtAll() {
        val s = screen(permissions = emptyList())
        assertEquals(0, s.moved(1).selectedIndex)
        assertEquals(0, s.moved(-1).selectedIndex)
    }

    @Test fun onlyRequiredPermissionsGateContinue() {
        assertFalse(screen(permissions = dualScreen).canContinue)
        assertTrue(screen(permissions = dualScreen, granted = setOf(storage)).canContinue)
        assertFalse(screen(permissions = dualScreen, granted = setOf(overlay)).canContinue)
        assertTrue(screen(permissions = emptyList()).canContinue)
    }

    // The three-way footer rule, one case per line.

    @Test fun ungrantedRequiredRowOffersGrant() {
        assertEquals(
            OnboardingPermissionsAction.GRANT,
            screen(permissions = dualScreen, granted = emptySet(), selectedIndex = 0).action,
        )
    }

    @Test fun ungrantedOptionalRowOffersGrant() {
        assertEquals(
            OnboardingPermissionsAction.GRANT,
            screen(permissions = dualScreen, granted = setOf(storage), selectedIndex = 1).action,
        )
    }

    @Test fun grantedRowOffersContinueOnceEveryRequiredPermissionIsGranted() {
        assertEquals(
            OnboardingPermissionsAction.CONTINUE,
            screen(permissions = dualScreen, granted = setOf(storage), selectedIndex = 0).action,
        )
        assertEquals(
            OnboardingPermissionsAction.CONTINUE,
            screen(permissions = singleScreen, granted = setOf(storage), selectedIndex = 0).action,
        )
    }

    @Test fun grantedRowOffersNothingWhileARequiredPermissionIsMissing() {
        assertEquals(
            OnboardingPermissionsAction.NONE,
            screen(permissions = dualScreen, granted = setOf(overlay), selectedIndex = 1).action,
        )
    }

    // Deliberate: the highlight sitting on an ungranted optional row reads GRANT even though
    // continue is available. Moving off that row is how the user advances.
    @Test fun anUngrantedOptionalRowHidesContinueRatherThanShowingBoth() {
        val ready = screen(permissions = dualScreen, granted = setOf(storage), selectedIndex = 1)
        assertTrue(ready.canContinue)
        assertEquals(OnboardingPermissionsAction.GRANT, ready.action)
        assertEquals(OnboardingPermissionsAction.CONTINUE, ready.moved(-1).action)
    }

    @Test fun anEmptyPermissionListOffersNothing() {
        assertEquals(OnboardingPermissionsAction.NONE, screen(permissions = emptyList()).action)
    }
}
