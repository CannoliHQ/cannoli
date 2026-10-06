package dev.cannoli.scorza.util

object ErrorLog {
    private val log = RotatingLogFile("error.log", 256L * 1024, "yyyy-MM-dd HH:mm:ss")

    fun init(cannoliRoot: String) = log.init(cannoliRoot)

    fun write(message: String) = log.write(message)

    fun error(message: String, throwable: Throwable) =
        write("$message: ${throwable.javaClass.simpleName}: ${throwable.message}")
}
