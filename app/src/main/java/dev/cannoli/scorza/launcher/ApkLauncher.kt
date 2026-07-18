package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
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
    private val activityDisplayRouter: ActivityDisplayRouter,
) {

    var debugLog: (String) -> Unit = {}
        set(value) { field = value; shellLauncher.debugLog = value }

    companion object {
        const val VIRTUAL_TV_SETTINGS_PACKAGE = "cannoli.virtual.tv_settings"
        private const val GAMEHUB_DETAIL_ACTIVITY =
            "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    }

    fun launch(packageName: String): LaunchResult {
        GameHubTarget.decode(packageName)?.let { game ->
            return launchGameHub(game)
        }
        AndroidShortcutTarget.decode(packageName)?.let { shortcut ->
            return launchShortcut(shortcut)
        }
        val intent = if (packageName == VIRTUAL_TV_SETTINGS_PACKAGE) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.AppNotInstalled(packageName)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return context.startActivityNoAnim(
            intent,
            "Failed to launch app",
            activityDisplayRouter.gameLaunchDisplayId(),
        )
    }

    private fun launchGameHub(target: GameHubTarget): LaunchResult {
        if (!context.isPackageInstalled(target.packageName)) {
            return LaunchResult.AppNotInstalled(target.packageName)
        }
        val intent = Intent().apply {
            component = ComponentName(target.packageName, GAMEHUB_DETAIL_ACTIVITY)
            putExtra("autoStartGame", true)
            putExtra("steamAppId", target.steamAppId)
            putExtra("id", "0")
            putExtra("type", "1")
            putExtra("gameType", "0")
            putExtra("localGameId", "")
            putExtra("localMobileAppId", "")
            putExtra("localAppName", target.title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.startActivityNoAnim(
            intent,
            "Failed to launch GameHub game",
            activityDisplayRouter.gameLaunchDisplayId(),
            logLabel = "gamehub",
        )
    }

    private fun launchShortcut(target: AndroidShortcutTarget): LaunchResult {
        if (!context.isPackageInstalled(target.packageName)) {
            return LaunchResult.AppNotInstalled(target.packageName)
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (!launcherApps.hasShortcutHostPermission()) {
            return LaunchResult.Error("Cannoli does not have access to Android shortcuts")
        }
        val query = LauncherApps.ShortcutQuery()
            .setPackage(target.packageName)
            .setShortcutIds(listOf(target.shortcutId))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val shortcut = runCatching {
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty().firstOrNull()
        }.getOrNull() ?: return LaunchResult.Error("Android shortcut is no longer available")
        val intent = shortcut.intents.orEmpty().lastOrNull()
            ?: return LaunchResult.Error("Android shortcut has no launch intent")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return context.startActivityNoAnim(
            intent,
            "Failed to launch shortcut",
            activityDisplayRouter.gameLaunchDisplayId(),
            logLabel = "shortcut",
        )
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
            LaunchMethod.SHELL -> shellLauncher.launch(
                ShellCommandFormatter.format(resolved, activityDisplayRouter.gameLaunchDisplayId())
            )
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
        val result = context.startActivityNoAnim(
            intent,
            "Failed to launch emulator",
            activityDisplayRouter.gameLaunchDisplayId(),
            logLabel = "emulator",
        )
        debugLog("  -> dispatch result=${result::class.simpleName}")
        return result
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

        val targetIntent = if (
            context.packageManager.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY) != null
        ) {
            viewIntent
        } else {
            debugLog("  -> FileProvider VIEW unresolved, using package launch intent")
            context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } ?: return LaunchResult.Error("No launch activity for $packageName")
        }
        return context.startActivityNoAnim(
            targetIntent,
            "Failed to launch emulator",
            activityDisplayRouter.gameLaunchDisplayId(),
            logLabel = "emulator-fallback",
        )
    }
}
