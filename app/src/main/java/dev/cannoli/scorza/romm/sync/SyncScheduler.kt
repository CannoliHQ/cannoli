package dev.cannoli.scorza.romm.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.ui.components.SaveSyncStatus
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
    private val http: dev.cannoli.scorza.romm.RommHttp,
    private val intervalMs: Long = 30 * 60 * 1000L,
    private val offlineRetryMs: Long = 30_000L,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var underlyingCallback: ConnectivityManager.NetworkCallback? = null
    private var loop: Job? = null
    private var lastSweepAt = 0L
    private var wasValidated = false
    private val sweeping = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resweepRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val forceCooldownMs = 60_000L

    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { triggerFromCallback() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !wasValidated) triggerFromCallback()
                wasValidated = validated
            }
            override fun onLost(network: Network) {
                wasValidated = false
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
        // The default-network callback is blind to wifi drops when a VPN (e.g. Tailscale) is the
        // default network: the VPN stays up across the reconnect, so no onLost/onAvailable fires.
        // Watch the underlying non-VPN internet transport so a physical reconnect still triggers.
        val underlyingCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dev.cannoli.scorza.util.RommLog.write("scheduler: underlying network available -> sync")
                // The pool can hold sockets to a network that is gone; reusing one stalls the next
                // request until its timeout instead of failing fast onto a fresh connection.
                evictStaleConnections()
                triggerFromCallback()
            }
            override fun onLost(network: Network) {
                dev.cannoli.scorza.util.RommLog.write("scheduler: underlying network lost -> re-check")
                evictStaleConnections()
                triggerFromCallback()
            }
        }
        val underlyingRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(underlyingRequest, underlyingCb) }
        underlyingCallback = underlyingCb
        // Poll on a short tick: retry every offlineRetryMs while OFFLINE (a safety net when the
        // callbacks miss a reconnect), otherwise fall back to the normal interval sweep.
        loop = scope.launch {
            while (true) {
                delay(offlineRetryMs)
                when {
                    statusHolder.state.value == SaveSyncStatus.OFFLINE -> trigger(force = true)
                    System.currentTimeMillis() - lastSweepAt >= intervalMs -> trigger(force = false)
                    else -> {}
                }
            }
        }
        statusHolder.settle(enabled = service.syncEnabled(), online = true, pendingConflicts = 0, hadError = false)
        dev.cannoli.scorza.util.RommLog.write("scheduler: started (default + underlying callbacks, ${offlineRetryMs / 1000}s poll)")
        trigger(force = false)
    }

    fun stop() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        underlyingCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        underlyingCallback = null
        wasValidated = false
        resweepRequested.set(false)
        loop?.cancel(); loop = null
        dev.cannoli.scorza.util.RommLog.write("scheduler: stopped")
    }

    /** Force an immediate sweep (e.g. on returning from a game), bypassing the debounce window. */
    fun syncNow() = trigger(force = true)

    // Registering the network callbacks makes them fire straight away, so every screen unlock used
    // to force several full sweeps. Callback- and start-driven sweeps wait out a cooldown; the
    // game-exit sync does not, because the save just changed.
    private fun triggerFromCallback() = trigger(force = true, cooldown = true)

    private fun evictStaleConnections() = runCatching { http.evictConnections() }

    private fun trigger(force: Boolean, cooldown: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !shouldSweep(now, lastSweepAt, intervalMs)) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger debounced (${(now - lastSweepAt) / 1000}s since last sweep)")
            return
        }
        if (cooldown && !pastForceCooldown(now, lastSweepAt, forceCooldownMs)) {
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger in cooldown (${(now - lastSweepAt) / 1000}s since last sweep)")
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
            // A forced request (network reconnect, game exit) that lands mid-sweep would
            // otherwise be lost; queue exactly one re-sweep for when the current one ends.
            if (force) resweepRequested.set(true)
            dev.cannoli.scorza.util.RommLog.write("scheduler: trigger ${if (force) "queued" else "skipped"}, a sweep is already running")
            return
        }
        lastSweepAt = now
        statusHolder.setActive(SaveSyncStatus.CHECKING)
        dev.cannoli.scorza.util.RommLog.write("scheduler: trigger fired (force=$force)")
        scope.launch {
            try {
                service.sweep(rommResolveGame(platformResolver, romDir()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                statusHolder.settle(
                    enabled = service.syncEnabled(),
                    online = true,
                    pendingConflicts = service.pendingConflictCount(),
                    hadError = true,
                )
            } finally {
                sweeping.set(false)
                if (resweepRequested.compareAndSet(true, false)) trigger(force = true)
            }
        }
    }

    companion object {
        fun shouldSweep(now: Long, lastSweepAt: Long, intervalMs: Long): Boolean = now - lastSweepAt >= intervalMs

        fun pastForceCooldown(now: Long, lastSweepAt: Long, cooldownMs: Long): Boolean =
            lastSweepAt == 0L || now - lastSweepAt >= cooldownMs
    }
}
