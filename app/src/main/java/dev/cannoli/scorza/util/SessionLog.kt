package dev.cannoli.scorza.util

import android.os.Build
import dev.cannoli.scorza.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLog(
    enabled: Boolean,
    private val cannoliRoot: String,
    private val coreName: String,
    private val corePath: String,
    private val romPath: String,
    private val gameTitle: String
) {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sink = LogSink(0)

    init {
        if (enabled && cannoliRoot.isNotEmpty()) {
            val file = try {
                val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                val dir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).coreLogDir(coreName)
                val name = normalizeGameName(gameTitle).ifEmpty { coreName }
                File(dir, "${ts}_${name}.log")
            } catch (_: Exception) {
                null
            }
            if (file != null) {
                sink.open(file)
                writeHeader()
            }
        }
    }

    private fun normalizeGameName(name: String): String =
        name.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_]"), "")

    // Sizing the ROM is a filesystem hit, so it happens on the writer thread with the rest
    // of the header rather than on whichever thread started the session.
    private fun writeHeader() {
        LogWriter.post {
            val romSize = try {
                File(romPath).takeIf { it.isFile }?.length()
            } catch (_: Exception) {
                null
            }
            LogWriter.write(sink, buildString {
                appendLine("=== Cannoli Session Log ===")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_HASH}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Core: $coreName ($corePath)")
                appendLine("ROM: $romPath")
                if (romSize != null) appendLine("ROM size: $romSize bytes")
                appendLine("Cannoli root: $cannoliRoot")
                appendLine("===========================")
                appendLine()
            })
        }
    }

    fun log(message: String) {
        if (!sink.isReady()) return
        LogWriter.write(sink, "${stamp()} $message\n")
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (!sink.isReady()) return
        LogWriter.write(sink, buildString {
            appendLine("${stamp()} ERROR: $message")
            if (throwable != null) appendLine(throwable.stackTraceToString())
        })
    }

    fun close() = sink.close()

    private fun stamp(): String = synchronized(fmt) { fmt.format(Date()) }
}
