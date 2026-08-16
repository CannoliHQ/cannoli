package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingPermission
import dev.cannoli.scorza.onboarding.OnboardingStep
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OnboardingPermissionsInputHandlerTest {

    private lateinit var nav: NavigationController
    private lateinit var onboarding: OnboardingCoordinator
    private lateinit var handler: OnboardingPermissionsInputHandler

    private val storage = OnboardingPermission.STORAGE
    private val overlay = OnboardingPermission.OVERLAY
    private val dualScreen = listOf(storage, overlay)

    @Before fun setup() {
        nav = NavigationController()
        onboarding = mockk<OnboardingCoordinator>(relaxed = true)
        handler = OnboardingPermissionsInputHandler(nav = nav, onboarding = onboarding)
    }

    private fun show(
        permissions: List<OnboardingPermission> = listOf(storage),
        granted: Set<OnboardingPermission> = emptySet(),
        selectedIndex: Int = 0,
    ) = nav.replaceTop(
        LauncherScreen.OnboardingPermissions(
            permissions = permissions,
            granted = granted,
            selectedIndex = selectedIndex,
        )
    )

    @Test fun confirmGrantsAnUngrantedPermission() {
        show()
        handler.onConfirm()
        verify { onboarding.requestPermission(storage) }
        verify(exactly = 0) { onboarding.show(any()) }
    }

    @Test fun confirmGrantsAnUngrantedOptionalPermissionRatherThanAdvancing() {
        show(permissions = dualScreen, granted = setOf(storage), selectedIndex = 1)
        handler.onConfirm()
        verify { onboarding.requestPermission(overlay) }
        verify(exactly = 0) { onboarding.show(any()) }
    }

    @Test fun confirmAdvancesFromAGrantedRowOnceRequiredPermissionsAreGranted() {
        show(granted = setOf(storage))
        handler.onConfirm()
        verify { onboarding.show(OnboardingStep.STORAGE) }
        verify(exactly = 0) { onboarding.requestPermission(any()) }
    }

    @Test fun confirmDoesNothingOnAGrantedRowWhileARequiredPermissionIsMissing() {
        show(permissions = dualScreen, granted = setOf(overlay), selectedIndex = 1)
        handler.onConfirm()
        verify(exactly = 0) { onboarding.show(any()) }
        verify(exactly = 0) { onboarding.requestPermission(any()) }
    }

    @Test fun movingOffTheOptionalRowIsWhatAdvances() {
        show(permissions = dualScreen, granted = setOf(storage), selectedIndex = 1)
        handler.onUp()
        assertEquals(0, (nav.currentScreen as LauncherScreen.OnboardingPermissions).selectedIndex)
        handler.onConfirm()
        verify { onboarding.show(OnboardingStep.STORAGE) }
    }

    @Test fun backReturnsToTheWelcomeStep() {
        show()
        handler.onBack()
        verify { onboarding.show(OnboardingStep.WELCOME) }
    }

    @Test fun movementWalksThePermissionList() {
        show(permissions = dualScreen)
        handler.onDown()
        assertEquals(1, (nav.currentScreen as LauncherScreen.OnboardingPermissions).selectedIndex)
        handler.onDown()
        assertEquals(1, (nav.currentScreen as LauncherScreen.OnboardingPermissions).selectedIndex)
        handler.onUp()
        assertEquals(0, (nav.currentScreen as LauncherScreen.OnboardingPermissions).selectedIndex)
    }
}
