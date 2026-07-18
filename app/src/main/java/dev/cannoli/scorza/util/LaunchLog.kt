package dev.cannoli.scorza.util

/**
 * Always-on, bounded diagnostics for activity and game launches.
 *
 * Launch failures often happen after Android accepts an intent, so they are not captured by
 * [ErrorLog]. Keeping this log ungated makes the next launch attempt diagnosable without asking
 * the user to reproduce the issue a second time with a logging toggle enabled.
 */
object LaunchLog {
    private val log = RotatingLogFile("launch.log", 512L * 1024, "yyyy-MM-dd HH:mm:ss.SSS")

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun write(message: String) = log.write(message)

    fun error(message: String, throwable: Throwable) =
        write("$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
}
