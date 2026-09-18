package dev.cannoli.scorza.achievements

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the unlocks earned offline when a network arrives, rather than waiting for the launcher to
 * come forward again.
 *
 * The drain otherwise runs on the launcher being resumed, which is every case but the one a player
 * actually hits: turning wifi back on while already looking at the game list produced nothing until
 * the next foreground transition.
 *
 * Registered only while the launcher is resumed. A game writes to this queue from the other
 * process, and the launcher is backgrounded for all of that, so listening through a session would
 * mean draining a queue while it is being written.
 */
class RaReconnectDrain(
    private val context: Context,
    private val scope: CoroutineScope,
    private val drain: suspend () -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wasValidated = false
    private val draining = AtomicBoolean(false)

    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = trigger()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // The transition only. This fires repeatedly for a network whose capabilities have
                // not changed in any way this cares about.
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !wasValidated) trigger()
                wasValidated = validated
            }

            override fun onLost(network: Network) {
                wasValidated = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        wasValidated = false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    /**
     * One reconnection reaches both callbacks, and a drain sends the whole queue, so a second pass
     * started while the first is in flight would be replaying what that one is already sending.
     */
    private fun trigger() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                drain()
            } finally {
                draining.set(false)
            }
        }
    }
}
