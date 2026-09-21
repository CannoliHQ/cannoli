package dev.cannoli.ricotta

import dev.cannoli.igm.RaSettingsHost
import dev.cannoli.igm.RaSettingsSweep
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the settings sweep when the card asks for it, and writes what it found.
 *
 * Asked for by a marker file rather than a menu row, because this is a diagnostic rather than a
 * feature: it belongs in nobody's in-game menu, and a file can be dropped over adb without a build
 * that has a debug surface in it.
 *
 *     adb shell touch <card>/Config/Internal/State/sweep_settings
 *
 * The marker is deleted as the sweep starts, so a crash mid-sweep does not run it again on the next
 * launch, which is the one behaviour that could make a bad setting stick.
 */
object RaSweepRunner {

    private const val MARKER = "Config/Internal/State/sweep_settings"
    private const val REPORT = "Logs/settings_sweep.log"

    fun runIfRequested(cannoliRoot: String, host: RaSettingsHost) {
        if (cannoliRoot.isEmpty()) return
        val marker = File(cannoliRoot, MARKER)
        if (!marker.isFile) return
        if (!marker.delete()) return
        Thread({ sweep(cannoliRoot, host) }, "ra-settings-sweep").start()
    }

    private fun sweep(cannoliRoot: String, host: RaSettingsHost) {
        val startedAt = System.currentTimeMillis()
        val report = try {
            val keys = RaSettingsSweep.discoverKeys(host)
            RaSettingsSweep(host).run(keys)
        } catch (e: Exception) {
            write(cannoliRoot, "settings sweep failed: ${e.javaClass.simpleName}: ${e.message}\n")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt))
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        write(cannoliRoot, "$stamp, ${seconds}s\n${report.text()}")
    }

    private fun write(cannoliRoot: String, text: String) {
        try {
            val out = File(cannoliRoot, REPORT)
            out.parentFile?.mkdirs()
            out.writeText(text)
        } catch (_: Exception) {
        }
    }
}
