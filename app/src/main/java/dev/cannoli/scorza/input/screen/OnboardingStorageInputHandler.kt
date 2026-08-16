package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.scorza.onboarding.OnboardingStorageAction
import dev.cannoli.scorza.setup.SetupCoordinator
import javax.inject.Inject

@ActivityScoped
class OnboardingStorageInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val onboarding: OnboardingCoordinator,
    private val setupCoordinator: SetupCoordinator,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.OnboardingStorage? =
        nav.currentScreen as? LauncherScreen.OnboardingStorage

    override fun onLeft() = cycleVolume(-1)

    override fun onRight() = cycleVolume(1)

    override fun onConfirm() {
        val screen = current() ?: return
        when (screen.action) {
            OnboardingStorageAction.SELECT_FOLDER -> nav.push(
                LauncherScreen.DirectoryBrowser(
                    purpose = BrowsePurpose.SETUP,
                    currentPath = "/storage/",
                    entries = setupCoordinator.listDirectories("/storage/"),
                )
            )
            OnboardingStorageAction.CONTINUE -> screen.targetPath?.let { onboarding.finish(it) }
            OnboardingStorageAction.NONE -> {}
        }
    }

    override fun onBack() {
        OnboardingStep.STORAGE.previous?.let { onboarding.show(it) }
    }

    private fun cycleVolume(delta: Int) {
        val screen = current() ?: return
        nav.replaceTop(screen.cycledVolume(delta))
    }
}
