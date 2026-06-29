package dev.cannoli.scorza.util

object RommLog {
    private val log = RotatingLogFile("romm.log", 1L * 1024 * 1024, "yyyy-MM-dd HH:mm:ss") { LoggingPrefs.romm }

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun write(message: String) = log.write(message)
}
