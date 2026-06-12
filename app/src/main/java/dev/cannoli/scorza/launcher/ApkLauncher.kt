package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.StrictMode
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.LaunchMethod
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellLauncher: ShellLauncher,
) {

    var debugLog: (String) -> Unit = {}
        set(value) { field = value; shellLauncher.debugLog = value }

    companion object {
        const val VIRTUAL_TV_SETTINGS_PACKAGE = "cannoli.virtual.tv_settings"
    }

    fun launch(packageName: String): LaunchResult {
        val intent = if (packageName == VIRTUAL_TV_SETTINGS_PACKAGE) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.AppNotInstalled(packageName)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return context.startActivityNoAnim(intent, "Failed to launch app")
    }

    fun launchWithRom(packageName: String, romFile: File, config: AppConfig): LaunchResult {
        debugLog("ApkLauncher.launchWithRom pkg=$packageName rom=${romFile.absolutePath} method=${config.launchMethod}")
        if (!context.isPackageInstalled(packageName)) {
            debugLog("  -> package not installed")
            return LaunchResult.AppNotInstalled(packageName)
        }
        val resolved = EmulatorIntentBuilder.resolve(context, config, romFile)
        return when (config.launchMethod) {
            LaunchMethod.INTENT -> dispatchIntent(resolved, config, romFile, packageName)
            LaunchMethod.SHELL  -> shellLauncher.launch(ShellCommandFormatter.format(resolved))
        }
    }

    private fun dispatchIntent(
        resolved: ResolvedIntent,
        config: AppConfig,
        romFile: File,
        packageName: String,
    ): LaunchResult {
        val intent = EmulatorIntentBuilder.toAndroidIntent(context, resolved, config)
        debugLog("  intent built: action=${intent.action} component=${intent.component?.flattenToShortString()}")
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            debugLog("  -> intent did not resolve; falling back to ACTION_VIEW + FileProvider")
            logExposedActivities(packageName)
            return launchViewWithFileProvider(packageName, romFile)
        }
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        val previousVmPolicy = if (resolved.dataUri?.scheme == "file") {
            val current = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            current
        } else null
        return try {
            context.startActivity(intent, opts)
            debugLog("  -> startActivity succeeded")
            LaunchResult.Success
        } catch (e: Exception) {
            debugLog("  -> startActivity failed: ${e.javaClass.simpleName}: ${e.message}")
            LaunchResult.Error(e.message ?: "Failed to launch emulator")
        } finally {
            previousVmPolicy?.let { StrictMode.setVmPolicy(it) }
        }
    }

    private fun logExposedActivities(packageName: String) {
        val probe = Intent().setPackage(packageName)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(probe, PackageManager.GET_RESOLVED_FILTER)
        if (resolved.isEmpty()) {
            debugLog("  exposed activities: <none discovered>")
            return
        }
        debugLog("  exposed activities for $packageName:")
        for (info in resolved) {
            val activity = info.activityInfo?.name ?: "?"
            val actions = info.filter?.let { f -> (0 until f.countActions()).map { f.getAction(it) } } ?: emptyList()
            debugLog("    - $activity actions=$actions")
        }
    }

    private fun launchViewWithFileProvider(packageName: String, romFile: File): LaunchResult {
        val uri = context.romFileProviderUri(romFile)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        return try {
            context.startActivity(viewIntent, opts)
            debugLog("  -> FileProvider VIEW startActivity succeeded")
            LaunchResult.Success
        } catch (_: Exception) {
            debugLog("  -> FileProvider VIEW failed, retrying via getLaunchIntentForPackage")
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
