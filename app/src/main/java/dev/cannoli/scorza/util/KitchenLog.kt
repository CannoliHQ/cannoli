package dev.cannoli.scorza.util

object KitchenLog {
    private val logFile = RotatingLogFile("kitchen.log", 1L * 1024 * 1024, "yyyy-MM-dd HH:mm:ss") { LoggingPrefs.kitchen }

    fun init(cannoliRoot: String) = logFile.init(cannoliRoot)

    fun log(message: String) = logFile.write(message)

    fun logError(message: String, throwable: Throwable? = null) {
        val detail = throwable?.let {
            "$message: ${it.javaClass.simpleName}: ${it.message}"
        } ?: message
        log("ERROR  $detail")
    }
}
