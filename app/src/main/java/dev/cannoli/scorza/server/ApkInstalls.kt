package dev.cannoli.scorza.server

import java.io.File

interface ApkInstalls {
    val stagingDir: File
    fun begin(apk: File): String
    fun status(installId: String): InstallStatus?
}

data class InstallStatus(val status: String, val message: String? = null) {
    companion object {
        const val PENDING_USER = "pending_user"
        const val SUCCESS = "success"
        const val FAILURE = "failure"
    }
}
