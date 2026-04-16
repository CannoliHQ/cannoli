package dev.cannoli.scorza.util

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private var writer: FileWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(cannoliRoot: String, enabled: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            try {
                val dir = File(cannoliRoot, "Logs")
                dir.mkdirs()
                val file = File(dir, "filescanner.log")
                writer = FileWriter(file, false)
            } catch (_: Exception) {
                writer = null
            }
        }
        write("DebugLog initialized")
    }

    fun write(msg: String) {
        synchronized(lock) {
            val w = writer ?: return
            w.appendLine("${fmt.format(Date())} $msg")
            w.flush()
        }
    }
}