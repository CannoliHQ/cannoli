package dev.cannoli.scorza.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.cannoli.scorza.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
/**
 * Keeps the process alive while the queue has work, and reports it in a notification.
 *
 * Two things are deliberately still RomM's. It also watches the art fetcher, because that runs
 * alongside a RomM download and has to hold the same notification: abstracting it would be
 * inventing an interface for one caller. And the notification strings keep their romm_ keys, since
 * those are translated through Crowdin and renaming a key orphans every translation of it.
 */
class DownloadService : Service() {

    @Inject lateinit var downloader: Downloader
    @Inject lateinit var artFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadManager.onServiceCreated(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.romm_download_notification_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.romm_download_notification_starting)))
        }
        watcher = scope.launch {
            combine(downloader.state, artFetcher.state) { items, art -> items to art }
                .collectLatest { (items, art) ->
                    val artRunning = art is dev.cannoli.scorza.romm.art.ArtFetchState.Running
                    if (!downloader.hasWork() && !artRunning) { stopSelf(); return@collectLatest }
                    if (artRunning) { notify(getString(R.string.romm_art_notification_active)); return@collectLatest }
                    val downloading = items.filter { it.status is DownloadStatus.Downloading }
                    val text = when {
                        downloading.size > 1 -> getString(R.string.romm_download_notification_count, downloading.size)
                        downloading.size == 1 -> {
                            val item = downloading.first()
                            val s = item.status as DownloadStatus.Downloading
                            val pct = if (s.total > 0) (s.downloaded * 100 / s.total) else 0
                            getString(R.string.romm_download_notification_progress, item.displayName, pct)
                        }
                        else -> getString(R.string.romm_download_notification_active)
                    }
                    notify(text)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        DownloadManager.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.romm_download_notification_title), NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.romm_download_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "romm_downloads"
        const val NOTIFICATION_ID = 0x520D
    }
}
