package dev.cannoli.scorza.romm.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SyncScheduler(
    private val context: Context,
    private val service: SaveSyncService,
    private val statusHolder: SaveSyncStatusHolder,
    private val platformResolver: PlatformConfig,
    private val settings: SettingsRepository,
    private val romDir: () -> File,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 30 * 60 * 1000L,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var loop: Job? = null
    private var lastSweepAt = 0L
    private val sweeping = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trigger(force = true) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) trigger(force = false)
            }
            override fun onLost(network: Network) {
                dev.cannoli.scorza.util.RommLog.write("scheduler: network lost -> OFFLINE")
                statusHolder.settle(
                    enabled = service.syncEnabled(),
                    online = false,
                    pendingConflicts = service.pendingConflictCount(),
                    hadError = false,
                )
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        callback = cb
        loop = scope.launch {
            while (true) { delay(intervalMs); trigger(force = false) }
        }
        val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        statusHolder.settle(enabled = service.syncEnabled(), online = online, pendingConflicts = 0, hadError = false)
        dev.cannoli.scorza.util.RommLog.write("scheduler: started (network callback + ${intervalMs / 60000}min loop)")
        trigger(force = false)
    }

    fun stop() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        loop?.cancel(); loop = null
        dev.cannoli.scorza.util.RommLog.write("scheduler: stopped")
    }

    /** Force an immediate sweep (e.g. on returning from a game), bypassing the debounce window. */
    fun syncNow() = trigger(force = true)

    private fun trigger(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && !shouldSweep(now, lastSweepAt, intervalMs)) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger debounced (${(now - lastSweepAt) / 1000}s since last sweep)")
            return
        }
        if (!service.syncEnabled()) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger skipped, sync disabled or not connected")
            return
        }
        if (service.deviceIdOrNull() == null) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger skipped, device not registered")
            return
        }
        if (!sweeping.compareAndSet(false, true)) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger skipped, a sweep is already running")
            return
        }
        lastSweepAt = now
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        dev.cannoli.scorza.util.RommLog.write("scheduler: trigger fired (force=$force online=$online)")
        scope.launch {
            try {
                service.sweep(rommResolveGame(platformResolver, romDir()), online = online)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                sweeping.set(false)
            }
        }
    }

    companion object {
        fun shouldSweep(now: Long, lastSweepAt: Long, intervalMs: Long): Boolean = now - lastSweepAt >= intervalMs
    }
}
