package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

class ApkLauncher(private val context: Context) {

    var debugLog: (String) -> Unit = {}

    companion object {
        const val VIRTUAL_TV_SETTINGS_PACKAGE = "cannoli.virtual.tv_settings"
    }

    enum class DataKind { NONE, SAF, PROVIDER, PATH }

    data class AppLaunchConfig(
        val activityName: String? = null,
        val action: String? = null,
        val data: DataKind = DataKind.NONE,
        val extraKey: String? = null,
        val extraKind: DataKind = DataKind.NONE
    )

    fun launch(packageName: String): LaunchResult {
        val intent = if (packageName == VIRTUAL_TV_SETTINGS_PACKAGE) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.AppNotInstalled(packageName)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, opts)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Failed to launch app")
        }
    }

    fun launchWithRom(
        packageName: String,
        romFile: File,
        config: AppLaunchConfig = AppLaunchConfig()
    ): LaunchResult {
        debugLog("ApkLauncher.launchWithRom pkg=$packageName rom=${romFile.absolutePath} config=$config")

        if (!context.isPackageInstalled(packageName)) {
            debugLog("  -> package not installed")
            return LaunchResult.AppNotInstalled(packageName)
        }

        if (config.data == DataKind.NONE && config.extraKey == null && config.action == null && config.activityName == null) {
            debugLog("  -> fallback ACTION_VIEW + FileProvider")
            return launchViewWithFileProvider(packageName, romFile)
        }

        val intent = Intent().apply {
            if (config.activityName != null) {
                component = ComponentName(packageName, config.activityName)
            } else {
                setPackage(packageName)
            }
            config.action?.let { action = it }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (config.data != DataKind.NONE) {
            val uri = buildUri(packageName, romFile, config.data, grantOnExtra = false, intent)
            intent.setDataAndType(uri, "*/*")
            if (config.data == DataKind.PROVIDER) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        if (config.extraKey != null && config.extraKind != DataKind.NONE) {
            val value = when (config.extraKind) {
                DataKind.PATH -> romFile.absolutePath
                else -> buildUri(packageName, romFile, config.extraKind, grantOnExtra = true, intent).toString()
            }
            intent.putExtra(config.extraKey, value)
        }

        logIntent(intent)

        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        val previousVmPolicy = if (config.data == DataKind.PATH) {
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

    @Suppress("DEPRECATION")
    private fun logIntent(intent: Intent) {
        val sb = StringBuilder("  intent:")
        sb.append(" action=").append(intent.action)
        sb.append(" component=").append(intent.component?.flattenToShortString())
        sb.append(" package=").append(intent.`package`)
        sb.append(" data=").append(intent.data)
        sb.append(" flags=0x").append(Integer.toHexString(intent.flags))
        val extras = intent.extras
        if (extras != null) {
            sb.append(" extras={")
            var first = true
            for (k in extras.keySet()) {
                if (!first) sb.append(", ")
                first = false
                sb.append(k).append('=').append(extras.get(k))
            }
            sb.append('}')
        }
        debugLog(sb.toString())
    }

    private fun launchViewWithFileProvider(packageName: String, romFile: File): LaunchResult {
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

        logIntent(viewIntent)

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

    private fun buildUri(
        packageName: String,
        romFile: File,
        kind: DataKind,
        grantOnExtra: Boolean,
        intent: Intent
    ): Uri = when (kind) {
        DataKind.SAF -> buildSafDocumentUri(romFile)
        DataKind.PROVIDER -> {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                romFile
            )
            if (grantOnExtra) {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            uri
        }
        DataKind.PATH -> Uri.fromFile(romFile)
        DataKind.NONE -> Uri.EMPTY
    }

    private fun buildSafDocumentUri(romFile: File): Uri {
        val absolute = romFile.absolutePath
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val (volumeId, relative) = when {
            absolute.startsWith("$primaryRoot/") ->
                "primary" to absolute.substring(primaryRoot.length + 1)
            absolute.startsWith("/storage/") -> {
                val after = absolute.removePrefix("/storage/")
                val slash = after.indexOf('/')
                if (slash < 0) "primary" to absolute.removePrefix("$primaryRoot/")
                else after.substring(0, slash) to after.substring(slash + 1)
            }
            else -> "primary" to absolute
        }
        val docId = "$volumeId:$relative"
        return Uri.parse("content://com.android.externalstorage.documents/document")
            .buildUpon()
            .appendPath(docId)
            .build()
    }
}
