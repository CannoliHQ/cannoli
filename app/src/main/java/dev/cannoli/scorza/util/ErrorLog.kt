package dev.cannoli.scorza.util

// Always-on error log. Unlike the other logs it is not gated behind a LoggingPrefs
// toggle and is not exposed in Settings: it should capture genuine failures (silently
// swallowed exceptions, skipped writes) on every device so they can be diagnosed.
object ErrorLog {
    private val log = RotatingLogFile("error.log", 256L * 1024, "yyyy-MM-dd HH:mm:ss")

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun write(message: String) = log.write(message)

    fun error(message: String, throwable: Throwable) =
        write("$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
}
