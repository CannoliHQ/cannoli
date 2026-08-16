package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingPermissionsAction
import dev.cannoli.scorza.onboarding.OnboardingStep
import javax.inject.Inject

@ActivityScoped
class OnboardingPermissionsInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val onboarding: OnboardingCoordinator,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.OnboardingPermissions? =
        nav.currentScreen as? LauncherScreen.OnboardingPermissions

    override fun onUp() {
        current()?.let { nav.replaceTop(it.moved(-1)) }
    }

    override fun onDown() {
        current()?.let { nav.replaceTop(it.moved(1)) }
    }

    // Runs whatever the footer offers for the highlighted row, from the screen's own value, so the
    // two can never disagree.
    override fun onConfirm() {
        val screen = current() ?: return
        when (screen.action) {
            OnboardingPermissionsAction.GRANT ->
                screen.focusedPermission?.let { onboarding.requestPermission(it) }
            OnboardingPermissionsAction.CONTINUE ->
                OnboardingStep.PERMISSIONS.next?.let { onboarding.show(it) }
            OnboardingPermissionsAction.NONE -> {}
        }
    }

    override fun onBack() {
        OnboardingStep.PERMISSIONS.previous?.let { onboarding.show(it) }
    }
}
