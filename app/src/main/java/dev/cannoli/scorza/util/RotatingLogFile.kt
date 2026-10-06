package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RotatingLogFile(
    private val fileName: String,
    maxBytes: Long,
    timestampPattern: String,
    private val gate: () -> Boolean = { true },
) {
    private val timestampFmt = SimpleDateFormat(timestampPattern, Locale.US)
    private val sink = LogSink(maxBytes)

    fun init(cannoliRoot: String) {
        val file = try {
            File(CannoliPaths(cannoliRoot).logsDir, fileName)
        } catch (_: Exception) {
            null
        }
        sink.open(file)
    }

    fun timestamp(): String = stamp()

    fun write(message: String) {
        if (!gate()) return
        if (!sink.isReady()) return
        LogWriter.write(sink, "${stamp()}  $message\n")
    }

    private fun stamp(): String = synchronized(timestampFmt) { timestampFmt.format(Date()) }
}
