package dev.cannoli.scorza.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.cannoli.igm.GuideDisplays
import dev.cannoli.scorza.R

enum class PermissionState { GRANTED, NOT_GRANTED, NOT_REQUIRED }

enum class PermissionGroup { ACTIONABLE, VERSION_GATED, ALWAYS_ON }

/**
 * Every permission the manifest declares, in the order the permissions page lists them.
 */
enum class AppPermission(
    val group: PermissionGroup,
    @param:StringRes val labelRes: Int,
    @param:StringRes val explanationRes: Int,
) {
    ALL_FILES_ACCESS(
        PermissionGroup.ACTIONABLE,
        R.string.permission_all_files,
        R.string.permission_why_all_files,
    ),
    DRAW_OVER_APPS(
        PermissionGroup.ACTIONABLE,
        R.string.permission_draw_over_apps,
        R.string.permission_why_draw_over_apps,
    ),
    INSTALL_UNKNOWN_APPS(
        PermissionGroup.ACTIONABLE,
        R.string.permission_install_unknown_apps,
        R.string.permission_why_install_unknown_apps,
    ),
    READ_STORAGE(
        PermissionGroup.VERSION_GATED,
        R.string.permission_read_storage,
        R.string.permission_why_read_storage,
    ),
    WRITE_STORAGE(
        PermissionGroup.VERSION_GATED,
        R.string.permission_write_storage,
        R.string.permission_why_write_storage,
    ),
    INTERNET(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_internet,
        R.string.permission_why_internet,
    ),
    NETWORK_STATE(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_network_state,
        R.string.permission_why_network_state,
    ),
    WIFI_STATE(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_wifi_state,
        R.string.permission_why_wifi_state,
    ),
    FOREGROUND_SERVICE(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_foreground_service,
        R.string.permission_why_foreground_service,
    ),
    FOREGROUND_SERVICE_DATA_SYNC(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_foreground_service_data_sync,
        R.string.permission_why_foreground_service_data_sync,
    ),
    WAKE_LOCK(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_wake_lock,
        R.string.permission_why_wake_lock,
    ),
    QUERY_ALL_PACKAGES(
        PermissionGroup.ALWAYS_ON,
        R.string.permission_query_all_packages,
        R.string.permission_why_query_all_packages,
    );

    /** Null when this build's Android version has no settings page to send the user to. */
    val settingsAction: String?
        get() = when (this) {
            ALL_FILES_ACCESS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                } else {
                    null
                }
            DRAW_OVER_APPS -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            INSTALL_UNKNOWN_APPS -> Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            else -> null
        }
}

/**
 * Version gating is answered before the grant is read, so a permission the manifest caps below
 * this device's API level reports as not required rather than as denied.
 */
fun AppPermission.state(context: Context): PermissionState = when (this) {
    AppPermission.ALL_FILES_ACCESS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            grantedIf(Environment.isExternalStorageManager())
        } else {
            PermissionState.NOT_REQUIRED
        }
    // Cannoli only draws an overlay to put a guide on a second screen, so on a single-screen device
    // there is nothing to grant it for. First run hides the row for the same reason.
    AppPermission.DRAW_OVER_APPS ->
        if (GuideDisplays.secondDisplayId(context) == null) {
            PermissionState.NOT_REQUIRED
        } else {
            grantedIf(Settings.canDrawOverlays(context))
        }
    AppPermission.INSTALL_UNKNOWN_APPS -> grantedIf(context.packageManager.canRequestPackageInstalls())
    AppPermission.READ_STORAGE -> when {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 -> PermissionState.NOT_REQUIRED
        // Where both exist, all files access covers everything this would, so it is only worth
        // reporting as the fallback for a device whose owner has not granted that.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() ->
            PermissionState.NOT_REQUIRED
        else -> heldBy(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    AppPermission.WRITE_STORAGE ->
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            heldBy(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            PermissionState.NOT_REQUIRED
        }
    AppPermission.INTERNET -> heldBy(context, Manifest.permission.INTERNET)
    AppPermission.WIFI_STATE -> heldBy(context, Manifest.permission.ACCESS_WIFI_STATE)
    AppPermission.WAKE_LOCK -> heldBy(context, Manifest.permission.WAKE_LOCK)
    AppPermission.FOREGROUND_SERVICE -> heldBy(context, Manifest.permission.FOREGROUND_SERVICE)
    AppPermission.FOREGROUND_SERVICE_DATA_SYNC ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            heldBy(context, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        } else {
            PermissionState.NOT_REQUIRED
        }
    AppPermission.NETWORK_STATE -> heldBy(context, Manifest.permission.ACCESS_NETWORK_STATE)
    AppPermission.QUERY_ALL_PACKAGES ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            heldBy(context, Manifest.permission.QUERY_ALL_PACKAGES)
        } else {
            PermissionState.NOT_REQUIRED
        }
}

fun permissionStates(context: Context): Map<AppPermission, PermissionState> =
    AppPermission.entries.associateWith { it.state(context) }

/**
 * What the permissions page lists on this device. One this Android version has no use for would
 * otherwise spend a row saying so, and the page is long enough without rows that report nothing.
 * The screen and its input handler both count through this, so selection cannot drift off the end.
 */
fun visiblePermissions(states: Map<AppPermission, PermissionState>): List<AppPermission> =
    AppPermission.entries.filter { states[it] != PermissionState.NOT_REQUIRED }

fun permissionSettingsIntent(action: String, context: Context): Intent =
    Intent(action, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun grantedIf(value: Boolean) =
    if (value) PermissionState.GRANTED else PermissionState.NOT_GRANTED

private fun heldBy(context: Context, permission: String) =
    grantedIf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
