package dev.cannoli.scorza.onboarding

import android.os.Build
import dev.cannoli.scorza.boot.PermissionStatus
import dev.cannoli.scorza.permissions.AppPermission

enum class OnboardingPermission(val required: Boolean) {
    STORAGE(required = true),
    OVERLAY(required = false);

    val appPermission: AppPermission
        get() = when (this) {
            STORAGE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AppPermission.ALL_FILES_ACCESS
                else AppPermission.READ_STORAGE
            OVERLAY -> AppPermission.DRAW_OVER_APPS
        }
}

fun onboardingPermissions(hasSecondDisplay: Boolean): List<OnboardingPermission> =
    OnboardingPermission.entries.filter { it != OnboardingPermission.OVERLAY || hasSecondDisplay }

fun grantedOnboardingPermissions(
    permissions: List<OnboardingPermission>,
    status: PermissionStatus,
): Set<OnboardingPermission> = permissions.filterTo(mutableSetOf()) {
    when (it) {
        OnboardingPermission.STORAGE -> status.hasStorage()
        OnboardingPermission.OVERLAY -> status.hasOverlay()
    }
}
