package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RotatingLogFile(
    private val fileName: String,
    private val maxBytes: Long,
    timestampPattern: String,
    private val gate: () -> Boolean = { true },
) {
    private val keepBytes = maxBytes / 2
    private val timestampFmt = SimpleDateFormat(timestampPattern, Locale.US)
    private val lock = Any()
    private var file: File? = null

    fun init(cannoliRoot: String) {
        synchronized(lock) {
            file = try {
                val dir = CannoliPaths(cannoliRoot).logsDir
                dir.mkdirs()
                File(dir, fileName)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun timestamp(): String = timestampFmt.format(Date())

    fun write(message: String) {
        if (!gate()) return
        synchronized(lock) {
            val f = file ?: return
            try {
                truncateIfTooBig(f)
                f.appendText("${timestampFmt.format(Date())}  $message\n")
            } catch (_: Exception) { }
        }
    }

    private fun truncateIfTooBig(f: File) {
        if (!f.exists() || f.length() <= maxBytes) return
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val len = raf.length()
                val cutFrom = len - keepBytes
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
