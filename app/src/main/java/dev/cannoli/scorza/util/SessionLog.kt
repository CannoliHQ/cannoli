package dev.cannoli.scorza.util

import android.os.Build
import dev.cannoli.scorza.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLog(
    enabled: Boolean,
    private val cannoliRoot: String,
    private val coreName: String,
    private val corePath: String,
    private val romPath: String,
    private val graphicsBackend: String
) {
    private var writer: FileWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    init {
        if (enabled && cannoliRoot.isNotEmpty()) {
            synchronized(lock) {
                try {
                    val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                    val dir = File(cannoliRoot, "Logs/$coreName")
                    dir.mkdirs()
                    val file = File(dir, "${ts}_${coreName}.log")
                    writer = FileWriter(file, false)
                    writeHeader()
                } catch (_: Exception) {
                    writer = null
                }
            }
        }
    }

    private fun writeHeader() {
        val w = writer ?: return
        w.appendLine("=== Cannoli Session Log ===")
        w.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_HASH}")
        w.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        w.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        w.appendLine("Core: $coreName ($corePath)")
        w.appendLine("ROM: $romPath")
        val romFile = File(romPath)
        if (romFile.exists()) w.appendLine("ROM size: ${romFile.length()} bytes")
        w.appendLine("Renderer: $graphicsBackend")
        w.appendLine("Cannoli root: $cannoliRoot")
        w.appendLine("===========================")
        w.appendLine()
        w.flush()
    }

    fun log(message: String) {
        synchronized(lock) {
            val w = writer ?: return
            w.appendLine("${fmt.format(Date())} $message")
            w.flush()
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val w = writer ?: return
            w.appendLine("${fmt.format(Date())} ERROR: $message")
            if (throwable != null) {
                w.appendLine(throwable.stackTraceToString())
            }
            w.flush()
        }
    }

    fun close() {
        synchronized(lock) {
            writer?.flush()
            writer?.close()
            writer = null
        }
    }
}
