package dev.cannoli.scorza.util

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Always-on error log. Unlike the other logs it is not gated behind a LoggingPrefs
// toggle and is not exposed in Settings: it should capture genuine failures (silently
// swallowed exceptions, skipped writes) on every device so they can be diagnosed.
object ErrorLog {
    private const val MAX_BYTES = 256L * 1024
    private const val KEEP_BYTES = MAX_BYTES / 2

    private var file: File? = null
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun init(cannoliRoot: String) {
        synchronized(lock) {
            try {
                val dir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).logsDir
                dir.mkdirs()
                file = File(dir, "error.log")
            } catch (_: Exception) {
                file = null
            }
        }
    }

    fun write(message: String) {
        synchronized(lock) {
            val f = file ?: return
            try {
                truncateIfTooBig(f)
                f.appendText("${timestampFmt.format(Date())}  $message\n")
            } catch (_: Exception) { }
        }
    }

    fun error(message: String, throwable: Throwable) {
        write("$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    private fun truncateIfTooBig(f: File) {
        if (!f.exists() || f.length() <= MAX_BYTES) return
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val len = raf.length()
                val cutFrom = len - KEEP_BYTES
                raf.seek(cutFrom)
                while (raf.filePointer < len) {
                    if (raf.readByte() == '\n'.code.toByte()) break
                }
                val keepStart = raf.filePointer
                val tail = ByteArray((len - keepStart).toInt())
                raf.readFully(tail)
                raf.seek(0)
                raf.write(tail)
                raf.setLength(tail.size.toLong())
            }
        } catch (_: Exception) { }
    }
}