package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.IgmInputMapping
import dev.cannoli.igm.RICOTTA_PROTOCOL_VERSION
import dev.cannoli.igm.RicottaLaunchParams
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.scorza.util.LaunchLog
import java.io.File

sealed interface ProtocolVerdict {
    data object Ok : ProtocolVerdict
    data class UpdateRicotta(val installed: Int, val required: Int) : ProtocolVerdict
    data class UpdateCannoli(val installed: Int, val required: Int) : ProtocolVerdict
}

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
)

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String,
    private val activityDisplayRouter: ActivityDisplayRouter,
) {
    // Managed RicottaArch: structured, version-negotiated launch contract that drives the
    // shared Cannoli in-game menu.
    fun launchRicotta(
        romFile: File,
        coreId: String,
        configPath: String? = null,
        targetPackage: String? = null,
        igm: RicottaIgm,
    ): LaunchResult {
        val pkg = targetPackage ?: getRetroArchPackage()
        if (!context.isPackageInstalled(pkg)) return LaunchResult.AppNotInstalled(pkg)

        when (checkProtocol(readInstalledProtocol(pkg), RICOTTA_PROTOCOL_VERSION)) {
            ProtocolVerdict.Ok -> Unit
            is ProtocolVerdict.UpdateRicotta -> return LaunchResult.Error(context.getString(dev.cannoli.scorza.R.string.launch_error_update_ricotta))
            is ProtocolVerdict.UpdateCannoli -> return LaunchResult.Error(context.getString(dev.cannoli.scorza.R.string.launch_error_update_cannoli))
        }

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
        )

        val intent = Intent().apply {
            component = ComponentName(pkg, "dev.cannoli.ricotta.RicottaLaunchActivity")
            params.writeToIntent(this)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launchDisplayId = activityDisplayRouter.gameLaunchDisplayId()
        logExternalLaunch("ricotta", pkg, romFile, coreId, configPath, intent, launchDisplayId)

        return context.startActivityNoAnim(
            intent,
            "Failed to launch RicottaArch",
            launchDisplayId = launchDisplayId,
            logLabel = "ricotta",
        )
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

        val launchDisplayId = activityDisplayRouter.gameLaunchDisplayId()
        logExternalLaunch("retroarch", pkg, romFile, coreId, configPath, intent, launchDisplayId)

        return context.startActivityNoAnim(
            intent,
            "Failed to launch RetroArch",
            launchDisplayId = launchDisplayId,
            logLabel = "retroarch",
        )
    }

    private fun logExternalLaunch(
        kind: String,
        pkg: String,
        romFile: File,
        coreId: String,
        configPath: String?,
        intent: Intent,
        launchDisplayId: Int?,
    ) {
        val resolved = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "unresolved"
        LaunchLog.write(
            "$kind request pkg=$pkg resolved=$resolved core=$coreId " +
                "rom=${romFile.absolutePath} exists=${romFile.exists()} readable=${romFile.canRead()} " +
                "size=${romFile.length()} config=${configPath ?: "<none>"} " +
                "displayPolicy=${launchDisplayId ?: "system-default"} " +
                activityDisplayRouter.diagnosticSummary()
        )
    }

    private fun readInstalledProtocol(pkg: String): Int = try {
        val ai = context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        ai.metaData?.getInt("cannoli.protocol", -1) ?: -1
    } catch (_: PackageManager.NameNotFoundException) {
        -1
    }

    companion object {
        fun isRicotta(pkg: String): Boolean = pkg.startsWith("dev.cannoli.ricotta")

        fun checkProtocol(installedProtocol: Int, requiredProtocol: Int): ProtocolVerdict = when {
            installedProtocol == requiredProtocol -> ProtocolVerdict.Ok
            installedProtocol < requiredProtocol -> ProtocolVerdict.UpdateRicotta(installedProtocol, requiredProtocol)
            else -> ProtocolVerdict.UpdateCannoli(installedProtocol, requiredProtocol)
        }
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun Context.startActivityNoAnim(
    intent: Intent,
    fallbackMsg: String,
    launchDisplayId: Int? = null,
    logLabel: String = "activity",
): LaunchResult {
    val component = intent.component?.flattenToShortString()
        ?: intent.`package`
        ?: intent.action
        ?: "<implicit>"
    LaunchLog.write(
        "$logLabel dispatch component=$component display=${launchDisplayId ?: "system-default"} " +
            "flags=0x${intent.flags.toString(16)} extras=${intent.extras?.keySet()?.sorted() ?: emptyList<String>()}"
    )
    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
        LaunchLog.write("$logLabel rejected unresolved component=$component")
        return LaunchResult.Error(fallbackMsg)
    }
    return try {
        val dispatchIntent = ExternalGameSessionActivity.wrap(this, intent, launchDisplayId)
        startActivity(dispatchIntent, noAnimationActivityOptions(launchDisplayId))
        LaunchLog.write("$logLabel accepted component=$component")
        LaunchResult.Success
    } catch (e: Exception) {
        LaunchLog.error("$logLabel failed component=$component", e)
        ErrorLog.error("$logLabel activity launch failed component=$component", e)
        LaunchResult.Error(e.message ?: fallbackMsg)
    }
}

fun Context.romFileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
