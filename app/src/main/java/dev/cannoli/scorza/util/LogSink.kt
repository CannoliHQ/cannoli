package dev.cannoli.scorza.util

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile

/**
 * A log file. Every method that touches the filesystem runs on the [LogWriter] thread,
 * so the mutable state below needs no locking: only that thread reads or writes it.
 *
 * [maxBytes] of 0 means the file never rotates.
 */
class LogSink(private val maxBytes: Long) : LogTarget {
    private val keepBytes = maxBytes / 2

    @Volatile private var ready = false

    private var file: File? = null
    private var out: BufferedWriter? = null
    private var written = 0L

    fun isReady(): Boolean = ready

    fun open(target: File?) {
        ready = target != null
        LogWriter.post {
            closeStream()
            file = target
            written = 0L
        }
    }

    fun close() {
        ready = false
        LogWriter.post {
            closeStream()
            file = null
        }
    }

    override fun append(line: String) {
        val f = file ?: return
        val w = out ?: try {
            f.parentFile?.mkdirs()
            written = if (f.exists()) f.length() else 0L
            BufferedWriter(FileWriter(f, true)).also { out = it }
        } catch (_: Exception) {
            return
        }
        try {
            w.write(line)
        } catch (_: Exception) {
            closeStream()
            return
        }
        // Log lines are effectively ASCII, so char count stands in for byte count here.
        // Rotation is a housekeeping threshold, not a hard cap, so the drift is harmless.
        written += line.length
        if (maxBytes > 0 && written > maxBytes) rotate()
    }

    override fun flush() {
        try {
            out?.flush()
        } catch (_: Exception) {
            closeStream()
        }
    }

    private fun rotate() {
        val f = file ?: return
        closeStream()
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
        written = try {
            if (f.exists()) f.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun closeStream() {
        try {
            out?.flush()
            out?.close()
        } catch (_: Exception) { }
        out = null
    }
}
