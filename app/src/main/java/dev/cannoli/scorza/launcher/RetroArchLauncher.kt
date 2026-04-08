package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

data class IGMExtras(
    val gameTitle: String = "",
    val stateBasePath: String = "",
    val cannoliRoot: String = "",
    val platformTag: String = "",
    val colorHighlight: String? = null,
    val colorText: String? = null,
    val colorHighlightText: String? = null,
    val colorAccent: String? = null,
    val colorTitle: String? = null
)

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String
) {
    fun launch(romFile: File, coreName: String, configPath: String? = null, targetPackage: String? = null, igm: IGMExtras? = null): LaunchResult {
        val retroArchPackage = targetPackage ?: getRetroArchPackage()
        if (!context.isPackageInstalled(retroArchPackage)) {
            return LaunchResult.AppNotInstalled(retroArchPackage)
        }

        val intent = Intent().apply {
            component = ComponentName(
                retroArchPackage,
                "com.retroarch.browser.retroactivity.RetroActivityFuture"
            )
            putExtra("LIBRETRO", "/data/data/$retroArchPackage/cores/${coreName}_android.so")
            putExtra("ROM", romFile.absolutePath)
            if (configPath != null) putExtra("CONFIGFILE", configPath)

            // IGM extras for RicottaArch
            if (igm != null) {
                putExtra("IGM_GAME_TITLE", igm.gameTitle)
                putExtra("IGM_STATE_BASE_PATH", igm.stateBasePath)
                putExtra("IGM_CANNOLI_ROOT", igm.cannoliRoot)
                putExtra("IGM_PLATFORM_TAG", igm.platformTag)
                igm.colorHighlight?.let { putExtra("IGM_COLOR_HIGHLIGHT", it) }
                igm.colorText?.let { putExtra("IGM_COLOR_TEXT", it) }
                igm.colorHighlightText?.let { putExtra("IGM_COLOR_HIGHLIGHT_TEXT", it) }
                igm.colorAccent?.let { putExtra("IGM_COLOR_ACCENT", it) }
                igm.colorTitle?.let { putExtra("IGM_COLOR_TITLE", it) }
            }

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, opts)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch RetroArch")
        }
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun PackageManager.isPackageInstalled(packageName: String): Boolean {
    return try {
        getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
