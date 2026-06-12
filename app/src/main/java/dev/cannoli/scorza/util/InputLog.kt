package dev.cannoli.scorza.util

object InputLog {
    private val log = RotatingLogFile("input.log", 512L * 1024, "HH:mm:ss.SSS") { LoggingPrefs.input }

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun write(message: String) = log.write(message)
}
