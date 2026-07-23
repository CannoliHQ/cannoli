package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.cannoli.igm.DelfinoLaunchParams

class DelfinoLauncher(private val context: Context) {

    fun launch(params: DelfinoLaunchParams, targetPackage: String): LaunchResult {
        if (!context.isPackageInstalled(targetPackage)) {
            return LaunchResult.AppNotInstalled(targetPackage)
        }
        return context.startActivityNoAnim(buildIntent(params, targetPackage), "Failed to launch Delfino")
    }

    companion object {
        const val ACTIVITY = "org.dolphinemu.dolphinemu.cannoli.DelfinoLaunchActivity"
        val PACKAGE_CANDIDATES = listOf("dev.cannoli.delfino.debug", "dev.cannoli.delfino")

        fun installedPackage(context: Context): String? =
            PACKAGE_CANDIDATES.firstOrNull { context.isPackageInstalled(it) }

        fun buildIntent(params: DelfinoLaunchParams, targetPackage: String): Intent =
            Intent().apply {
                component = ComponentName(targetPackage, ACTIVITY)
                params.writeToIntent(this)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
