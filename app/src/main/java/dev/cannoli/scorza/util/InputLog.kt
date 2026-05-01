package dev.cannoli.scorza.util

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InputLog {
    private const val MAX_BYTES = 512L * 1024
    private const val KEEP_BYTES = MAX_BYTES / 2

    private var file: File? = null
    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(cannoliRoot: String) {
        synchronized(lock) {
            try {
                val dir = dev.cannoli.scorza.config.CannoliPaths(cannoliRoot).logsDir
                dir.mkdirs()
                file = File(dir, "input.log")
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
