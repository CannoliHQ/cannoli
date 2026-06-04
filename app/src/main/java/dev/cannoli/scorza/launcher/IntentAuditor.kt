package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.LaunchMethod
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentAuditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platformConfig: PlatformConfig,
    private val settings: SettingsRepository,
) {

    data class Result(val reportFile: File, val totalInstalled: Int, val totalFailed: Int)

    fun runAudit(): Result {
        val pm = context.packageManager
        val sample = sampleRom()
        val sb = StringBuilder()
        sb.appendLine("Cannoli Intent Audit - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("====================================================")
        sb.appendLine("Note: MANAGE_EXTERNAL_STORAGE is shown as DECLARED only; it may not be GRANTED at runtime.")
        sb.appendLine()

        var totalInstalled = 0
        var totalFailed = 0

        val tags = platformConfig.getAllTags().sorted()
        for (tag in tags) {
            val configs = platformConfig.getAppOptions(tag)
            if (configs.isEmpty()) continue
            sb.appendLine("$tag (${platformConfig.getDisplayName(tag)})")
            for (cfg in configs) {
                val installed = pm.isPackageInstalled(cfg.packageName)
                val label = if (installed) resolveAppLabel(pm, cfg.packageName) else cfg.packageName
                sb.appendLine("  ${cfg.packageName}  [${if (installed) "INSTALLED" else "NOT INSTALLED"}]${if (installed && label != cfg.packageName) " ($label)" else ""}")
                if (!installed) continue

                totalInstalled++
                sb.appendLine("    action:   ${cfg.action}")
                sb.appendLine("    activity: ${cfg.activity ?: "<none>"}")
                sb.appendLine("    data:     ${cfg.data}")
                sb.appendLine("    extras:   ${if (cfg.extras.isEmpty()) "<none>" else cfg.extras.joinToString { "${it.key} (${it.kind})" }}")
                sb.appendLine("    method:   ${cfg.launchMethod}")

                val resolvedIntent = EmulatorIntentBuilder.resolve(context, cfg, sample)
                val intent = EmulatorIntentBuilder.toAndroidIntent(context, resolvedIntent, cfg)
                val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolved != null) {
                    sb.appendLine("    resolved: ${resolved.activityInfo.packageName}/${resolved.activityInfo.name}")
                } else {
                    totalFailed++
                    sb.appendLine("    resolved: NO (intent will not launch)")
                    sb.appendLine("    exposed activities:")
                    val exposed = exposedActivities(cfg.packageName)
                    if (exposed.isEmpty()) {
                        sb.appendLine("      <none discovered>")
                    } else {
                        for (line in exposed) sb.appendLine("      - $line")
                    }
                }

                if (cfg.launchMethod == LaunchMethod.SHELL && hasContentUri(resolvedIntent)) {
                    val mes = pm.getPackageInfo(cfg.packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.contains(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
                    if (!mes) {
                        sb.appendLine("    warning:  shell launch with content URI but target does not declare MANAGE_EXTERNAL_STORAGE")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("====================================================")
        sb.appendLine("Installed emulators audited: $totalInstalled")
        sb.appendLine("Intents that failed to resolve: $totalFailed")

        val reportFile = reportFile()
        reportFile.parentFile?.mkdirs()
        if (reportFile.exists()) reportFile.delete()
        reportFile.writeText(sb.toString())
        return Result(reportFile, totalInstalled, totalFailed)
    }

    private fun reportFile(): File =
        File(CannoliPaths(File(settings.sdCardRoot)).logsDir, "intent_audit.txt")

    private fun sampleRom(): File {
        val dir = File(context.cacheDir, "intent_audit").apply { mkdirs() }
        val f = File(dir, "sample.rom")
        if (!f.exists()) f.writeBytes(byteArrayOf(0))
        return f
    }

    private fun hasContentUri(resolved: ResolvedIntent): Boolean {
        if (resolved.dataUri?.scheme == "content") return true
        return resolved.extras.any { e ->
            when (e) {
                is ResolvedExtra.UriExtra -> e.value.scheme == "content"
                is ResolvedExtra.StringExtra -> e.value.startsWith("content://")
                is ResolvedExtra.StringArrayExtra -> e.values.any { it.startsWith("content://") }
            }
        }
    }

    private fun exposedActivities(packageName: String): List<String> {
        val probe = Intent().setPackage(packageName)
        @Suppress("DEPRECATION")
        val infos = context.packageManager.queryIntentActivities(probe, PackageManager.GET_RESOLVED_FILTER)
        return infos.map { info ->
            val activity = info.activityInfo?.name ?: "?"
            val actions = info.filter?.let { f -> (0 until f.countActions()).map { f.getAction(it) } } ?: emptyList()
            "$activity actions=$actions"
        }
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
