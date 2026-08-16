package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingStep
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

    override fun onUp() {
        current()?.let { nav.replaceTop(it.moved(-1)) }
    }

    override fun onDown() {
        current()?.let { nav.replaceTop(it.moved(1)) }
    }

    // The custom row is the only one confirm has anywhere to take: every other row already names
    // the volume the highlight sits on.
    override fun onConfirm() {
        val screen = current() ?: return
        if (!screen.isCustomVolume) return
        nav.push(
            LauncherScreen.DirectoryBrowser(
                purpose = BrowsePurpose.SETUP,
                currentPath = "/storage/",
                entries = setupCoordinator.listDirectories("/storage/"),
            )
        )
    }

    // Finishing setup is deliberate, so it sits on START rather than on the confirm button every
    // other step advances with.
    override fun onStart() {
        current()?.targetPath?.let { onboarding.finish(it) }
    }

    override fun onBack() {
        OnboardingStep.STORAGE.previous?.let { onboarding.show(it) }
    }
}
