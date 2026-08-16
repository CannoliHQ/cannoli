package dev.cannoli.scorza.onboarding

import android.os.Build
import dev.cannoli.scorza.boot.PermissionStatus
import dev.cannoli.scorza.permissions.AppPermission

enum class OnboardingPermission(val required: Boolean) {
    STORAGE(required = true),
    OVERLAY(required = false);

    /**
     * The catalogue entry this row presents. Storage is a different manifest permission below
     * API 30, where all files access does not exist, so the row names whichever one is real here.
     */
    val appPermission: AppPermission
        get() = when (this) {
            STORAGE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AppPermission.ALL_FILES_ACCESS
                else AppPermission.READ_STORAGE
            OVERLAY -> AppPermission.DRAW_OVER_APPS
        }
}

/**
 * The overlay permission only buys anything on a dual-screen device, so a single-screen device is
 * never offered it. Guides fall back to the main screen when it is missing, which is why it is
 * optional rather than a second required grant.
 */
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
