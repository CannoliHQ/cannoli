package dev.cannoli.scorza.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.KitchenLog
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class KitchenService : Service() {

    @Inject lateinit var romsRepository: RomsRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scanScheduler: ScanScheduler

    private var server: KitchenHttpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        KitchenManager.onServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        val pin = intent?.getStringExtra(EXTRA_PIN).orEmpty()
        val codeBypass = intent?.getBooleanExtra(EXTRA_CODE_BYPASS, false) ?: false
        startServer(pin, codeBypass)
        return START_NOT_STICKY
    }

    private fun startServer(pin: String, codeBypass: Boolean) {
        if (server != null) return
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireLocks()
        val root = File(settings.sdCardRoot)
        val romsRoot = {
            settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) }
                ?: File(root, "Roms")
        }
        val s = KitchenHttpServer(
            cannoliRoot = root,
            assets = assets,
            romsRootProvider = romsRoot,
            codeBypass = codeBypass,
            pin = pin,
            romsRepository = romsRepository,
            scanPlatform = { tag -> scanScheduler.runNow(tag) },
        )
        try {
            s.startServer()
            server = s
        } catch (e: Exception) {
            KitchenLog.logError("service failed to start server", e)
            notifyError()
            stopEverything()
        }
    }

    fun setCodeBypass(enabled: Boolean) { server?.codeBypass = enabled }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "cannoli:kitchen").apply {
            setReferenceCounted(false); acquire()
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cannoli:kitchen").apply {
            setReferenceCounted(false); acquire()
        }
        KitchenLog.log("wifi lock and wake lock acquired")
    }

    private fun releaseLocks() {
        val hadLocks = wifiLock != null || wakeLock != null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        if (hadLocks) KitchenLog.log("wifi lock and wake lock released")
    }

    private fun stopEverything() {
        server?.stopServer()
        server = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stopServer()
        server = null
        releaseLocks()
        KitchenManager.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nonna's Kitchen", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        ensureChannel()
        val stopIntent = Intent(this, KitchenService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nonna's Kitchen is running")
            .setContentText("File server active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun notifyError() {
        ensureChannel()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nonna's Kitchen could not start")
                .setContentText("The file server port is unavailable")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .build()
        )
    }

    companion object {
        const val ACTION_STOP = "dev.cannoli.scorza.server.KitchenService.STOP"
        const val EXTRA_PIN = "pin"
        const val EXTRA_CODE_BYPASS = "code_bypass"
        private const val CHANNEL_ID = "kitchen"
        private const val NOTIFICATION_ID = 4201
    }
}
