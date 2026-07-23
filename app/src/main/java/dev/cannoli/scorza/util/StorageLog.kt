package dev.cannoli.scorza.util

object StorageLog {
    private val log = RotatingLogFile("storage.log", 256L * 1024, "yyyy-MM-dd HH:mm:ss") { LoggingPrefs.storage }

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun startRun(label: String) = log.write("=== $label @ ${log.timestamp()} ===")

    fun write(message: String) = log.write(message)
}
