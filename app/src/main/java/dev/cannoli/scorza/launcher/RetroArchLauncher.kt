package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.IgmInputMapping
import dev.cannoli.igm.RicottaLaunchParams
import dev.cannoli.scorza.i18n.LocaleOverride
import java.io.File

data class RicottaIgm(
    val gameTitle: String,
    val stateBasePath: String,
    val cannoliRoot: String,
    val platformTag: String,
    val platformName: String,
    val igmTriggerKeycodes: List<Int>,
    val quitOnFocusLoss: Boolean = true,
    val preferredRefreshRate: Int? = null,
    val colors: IgmColors? = null,
    val displaySettings: IgmDisplaySettings,
    val inputMapping: IgmInputMapping? = null,
    val romBaseName: String = "",
    val hardcoreInEffect: Boolean = false,
)

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String,
) {
    // Managed RicottaArch: structured launch contract that drives the shared Cannoli in-game
    // menu, targeting the RetroArch component embedded in this APK.
    fun launchRicotta(
        romFile: File,
        coreId: String,
        configPath: String? = null,
        igm: RicottaIgm,
    ): LaunchResult {
        val params = RicottaLaunchParams(
            coreId = coreId,
            romPath = romFile.absolutePath,
            configFilePath = configPath,
            gameTitle = igm.gameTitle,
            stateBasePath = igm.stateBasePath,
            cannoliRoot = igm.cannoliRoot,
            platformTag = igm.platformTag,
            platformName = igm.platformName,
            igmTriggerKeycodes = igm.igmTriggerKeycodes,
            quitOnFocusLoss = igm.quitOnFocusLoss,
            preferredRefreshRate = igm.preferredRefreshRate,
            colors = igm.colors,
            displaySettings = igm.displaySettings,
            inputMapping = igm.inputMapping,
            localeTag = LocaleOverride.currentTag(context),
            romBaseName = igm.romBaseName,
            hardcoreInEffect = igm.hardcoreInEffect,
        )

        val intent = Intent().apply {
            component = ComponentName(context, "dev.cannoli.ricotta.RicottaLaunchActivity")
            params.writeToIntent(this)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return context.startActivityNoAnim(intent, "Failed to launch RicottaArch")
    }

    // Stock RetroArch (DIY): the classic RetroActivityFuture intent. The user owns RetroArch,
    // so there is no Cannoli in-game menu and no protocol negotiation.
    fun launchRetroArchIntent(
        romFile: File,
        coreId: String,
        configPath: String? = null,
        targetPackage: String? = null,
    ): LaunchResult {
        val pkg = targetPackage ?: getRetroArchPackage()
        if (!context.isPackageInstalled(pkg)) return LaunchResult.AppNotInstalled(pkg)

        val intent = Intent().apply {
            component = ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
            putExtra("LIBRETRO", "/data/data/$pkg/cores/${coreId}_android.so")
            putExtra("ROM", romFile.absolutePath)
            if (configPath != null) putExtra("CONFIGFILE", configPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return context.startActivityNoAnim(intent, "Failed to launch RetroArch")
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun Context.startActivityNoAnim(intent: Intent, fallbackMsg: String): LaunchResult = try {
    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
    LaunchResult.Success
} catch (e: Exception) {
    LaunchResult.Error(e.message ?: fallbackMsg)
}

fun Context.romFileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
