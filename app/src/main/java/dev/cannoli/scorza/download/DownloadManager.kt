package dev.cannoli.scorza.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object DownloadManager {
    @Volatile private var service: DownloadService? = null

    fun ensureStarted(context: Context) {
        ContextCompat.startForegroundService(
            context, Intent(context, DownloadService::class.java)
        )
    }

    internal fun onServiceCreated(s: DownloadService) { service = s }
    internal fun onServiceDestroyed(s: DownloadService) { if (service === s) service = null }
}
