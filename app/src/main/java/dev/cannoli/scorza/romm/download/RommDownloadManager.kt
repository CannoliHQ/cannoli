package dev.cannoli.scorza.romm.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object RommDownloadManager {
    @Volatile private var service: RommDownloadService? = null

    fun ensureStarted(context: Context) {
        ContextCompat.startForegroundService(
            context, Intent(context, RommDownloadService::class.java)
        )
    }

    internal fun onServiceCreated(s: RommDownloadService) { service = s }
    internal fun onServiceDestroyed(s: RommDownloadService) { if (service === s) service = null }
}
