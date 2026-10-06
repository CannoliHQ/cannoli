package dev.cannoli.scorza.achievements

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
