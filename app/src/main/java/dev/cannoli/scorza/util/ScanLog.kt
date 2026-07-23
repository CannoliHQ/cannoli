package dev.cannoli.scorza.util

object ScanLog {
    private val log = RotatingLogFile("scan.log", 1L * 1024 * 1024, "yyyy-MM-dd HH:mm:ss") { LoggingPrefs.romScan }

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun startRun(label: String) = log.write("=== $label @ ${log.timestamp()} ===")

    fun write(message: String) = log.write(message)
}
