package dev.cannoli.scorza.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.igm.GuideDisplays
import dev.cannoli.scorza.boot.PermissionStatus
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.setup.SetupCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// Sentinel for the picker entry that has no volume of its own. Never rendered: the row shows the
// path the user browsed to, or "not selected" until they pick one.
private const val CUSTOM_VOLUME = "Custom"

/**
 * Owns which first-run step is showing. Steps are named rather than inferred from boot state, so
 * both `NeedsPermission` and `NeedsSetup` enter the same wizard at step one.
 */
@ActivityScoped
class OnboardingCoordinator @Inject constructor(
    private val nav: NavigationController,
    private val setupCoordinator: SetupCoordinator,
    private val permissionStatus: PermissionStatus,
    @ApplicationContext private val context: Context,
) {
    var onRequestPermission: ((OnboardingPermission) -> Unit)? = null
    var onFinished: ((targetPath: String) -> Unit)? = null

    private val _welcomePress = MutableStateFlow<WelcomePress?>(null)

    /**
     * The press that advanced the welcome step, kept rather than discarded so the input mapping
     * work can identify the active controller from it. Nothing consumes it yet.
     */
    val welcomePress: StateFlow<WelcomePress?> = _welcomePress.asStateFlow()

    fun start() {
        val top = nav.currentScreen
        // The legend wizard is step one's recovery path, so a boot-state change while it is up must
        // not wipe the stack out from under it.
        if (top is LauncherScreen.OnboardingScreen ||
            top is LauncherScreen.DirectoryBrowser ||
            top is LauncherScreen.LegendWizard
        ) {
            refresh()
            return
        }
        nav.screenStack.clear()
        nav.screenStack.add(LauncherScreen.OnboardingWelcome)
    }

    /** Advances off the welcome step. Ignores presses arriving once it has already been left. */
    fun onWelcomePress(deviceId: Int, keyCode: Int) {
        if (nav.currentScreen !is LauncherScreen.OnboardingWelcome) return
        _welcomePress.value = WelcomePress(deviceId, keyCode)
        OnboardingStep.WELCOME.next?.let { show(it) }
    }

    /** Grants land as a return from a system settings page, so the step rereads on resume. */
    fun refresh() {
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        nav.replaceTop(permissionsScreen(screen.selectedIndex))
    }

    fun show(step: OnboardingStep) {
        nav.replaceTop(
            when (step) {
                OnboardingStep.WELCOME -> LauncherScreen.OnboardingWelcome
                OnboardingStep.PERMISSIONS -> permissionsScreen()
                OnboardingStep.STORAGE -> storageScreen()
            }
        )
    }

    fun requestPermission(permission: OnboardingPermission) {
        onRequestPermission?.invoke(permission)
    }

    fun finish(targetPath: String) {
        onFinished?.invoke(targetPath)
    }

    private fun permissionsScreen(selectedIndex: Int = 0): LauncherScreen.OnboardingPermissions {
        val permissions = onboardingPermissions(
            hasSecondDisplay = GuideDisplays.secondDisplayId(context) != null
        )
        return LauncherScreen.OnboardingPermissions(
            permissions = permissions,
            granted = grantedOnboardingPermissions(permissions, permissionStatus),
            selectedIndex = selectedIndex.coerceIn(0, (permissions.size - 1).coerceAtLeast(0)),
        )
    }

    private fun storageScreen(): LauncherScreen.OnboardingStorage {
        val volumes = setupCoordinator.detectStorageVolumes() + (CUSTOM_VOLUME to "")
        val existingIndex = setupCoordinator.existingCannoliVolumeIndex(volumes)
        return LauncherScreen.OnboardingStorage(
            volumes = volumes,
            volumeIndex = existingIndex ?: 0,
            existingInstallVolumeIndex = existingIndex,
        )
    }
}
