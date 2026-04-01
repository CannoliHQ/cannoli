package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
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

    fun launchWithRom(packageName: String, romFile: File): LaunchResult {
        if (!context.isPackageInstalled(packageName)) {
            return LaunchResult.AppNotInstalled(packageName)
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            romFile
        )

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setPackage(packageName)
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
}
