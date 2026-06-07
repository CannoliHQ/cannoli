package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.RICOTTA_PROTOCOL_VERSION
import dev.cannoli.igm.RicottaLaunchParams
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
    val igmTriggerKeycodes: List<Int>,
    val quitOnFocusLoss: Boolean = true,
    val preferredRefreshRate: Int? = null,
    val colors: IgmColors? = null,
)

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String,
) {
    fun launch(
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
            is ProtocolVerdict.UpdateRicotta -> return LaunchResult.Error("Update RicottaArch to continue")
            is ProtocolVerdict.UpdateCannoli -> return LaunchResult.Error("Update Cannoli to continue")
        }

        val params = RicottaLaunchParams(
            coreId = coreId,
            romPath = romFile.absolutePath,
            configFilePath = configPath,
            gameTitle = igm.gameTitle,
            stateBasePath = igm.stateBasePath,
            cannoliRoot = igm.cannoliRoot,
            platformTag = igm.platformTag,
            igmTriggerKeycodes = igm.igmTriggerKeycodes,
            quitOnFocusLoss = igm.quitOnFocusLoss,
            preferredRefreshRate = igm.preferredRefreshRate,
            colors = igm.colors,
        )

        val intent = Intent().apply {
            component = ComponentName(pkg, "dev.cannoli.ricotta.RicottaLaunchActivity")
            params.writeToIntent(this)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, opts)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch RicottaArch")
        }
    }

    private fun readInstalledProtocol(pkg: String): Int = try {
        val ai = context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        ai.metaData?.getInt("cannoli.protocol", -1) ?: -1
    } catch (_: PackageManager.NameNotFoundException) {
        -1
    }

    companion object {
        fun checkProtocol(installedProtocol: Int, requiredProtocol: Int): ProtocolVerdict = when {
            installedProtocol == requiredProtocol -> ProtocolVerdict.Ok
            installedProtocol < requiredProtocol -> ProtocolVerdict.UpdateRicotta(installedProtocol, requiredProtocol)
            else -> ProtocolVerdict.UpdateCannoli(installedProtocol, requiredProtocol)
        }
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
