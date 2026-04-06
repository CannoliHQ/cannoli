package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class ApkLauncher(private val context: Context) {

    fun launch(packageName: String): LaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.AppNotInstalled(packageName)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, opts)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch app")
        }
    }

    data class AppLaunchConfig(
        val activityName: String? = null,
        val action: String? = null,
        val pathExtra: String? = null
    )

    fun launchWithRom(
        packageName: String,
        romFile: File,
        config: AppLaunchConfig = AppLaunchConfig()
    ): LaunchResult {
        if (!context.isPackageInstalled(packageName)) {
            return LaunchResult.AppNotInstalled(packageName)
        }

        if (config.pathExtra != null) {
            return launchWithPathExtra(packageName, romFile, config)
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            romFile
        )

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            if (config.activityName != null) {
                component = ComponentName(packageName, config.activityName)
            } else {
                setPackage(packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()

        return try {
            context.startActivity(viewIntent, opts)
            LaunchResult.Success
        } catch (_: Exception) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.Error("No launch activity for $packageName")
            launchIntent.apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(launchIntent, opts)
                LaunchResult.Success
            } catch (e: Exception) {
                LaunchResult.Error(e.message ?: "Failed to launch emulator")
            }
        }
    }

    private fun launchWithPathExtra(
        packageName: String,
        romFile: File,
        config: AppLaunchConfig
    ): LaunchResult {
        val action = config.action ?: Intent.ACTION_MAIN
        val intent = Intent(action).apply {
            if (config.activityName != null) {
                component = ComponentName(packageName, config.activityName)
            } else {
                setPackage(packageName)
            }
            putExtra(config.pathExtra, romFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, opts)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch emulator")
        }
    }
}
