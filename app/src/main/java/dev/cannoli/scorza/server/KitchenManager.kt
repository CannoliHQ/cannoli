package dev.cannoli.scorza.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.cannoli.ui.components.NetworkEndpoint
import dev.cannoli.ui.components.interfaceKindFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object KitchenManager {

    const val PORT = 1091

    @Volatile private var service: KitchenService? = null
    @Volatile private var codeBypass = false
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    var pin: String = ""
        private set

    val isRunning: Boolean get() = _running.value

    fun start(context: Context, codeBypass: Boolean) {
        if (_running.value) return
        this.codeBypass = codeBypass
        pin = generatePin()
        _running.value = true
        val intent = Intent(context, KitchenService::class.java)
            .putExtra(KitchenService.EXTRA_PIN, pin)
            .putExtra(KitchenService.EXTRA_CODE_BYPASS, codeBypass)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        _running.value = false
        context.startService(
            Intent(context, KitchenService::class.java).setAction(KitchenService.ACTION_STOP)
        )
    }

    fun setCodeBypass(enabled: Boolean) { codeBypass = enabled; service?.setCodeBypass(enabled) }

    internal fun onServiceCreated(s: KitchenService) { service = s }

    internal fun onServiceDestroyed(s: KitchenService) {
        if (service === s) { service = null; _running.value = false }
    }

    fun pinForDisplay(): String? = pinForDisplayValue(isRunning, codeBypass, pin)

    internal fun pinForDisplayValue(running: Boolean, codeBypass: Boolean, pin: String): String? =
        pin.takeIf { running && !codeBypass && pin.isNotEmpty() }

    fun endpoints(hasVpn: Boolean): List<NetworkEndpoint> = endpointsFrom(enumerateLocalPairs(hasVpn))

    internal fun endpointsFrom(pairs: List<Pair<String, String>>): List<NetworkEndpoint> =
        pairs.map { (name, ip) -> NetworkEndpoint(interfaceKindFor(name), ip, "$ip:$PORT") }

    fun getUrls(hasVpn: Boolean): List<String> {
        val ips = enumerateLocalPairs(hasVpn).map { it.second }
        if (ips.isEmpty()) return listOf("http://?.?.?.?:$PORT")
        return ips.map { "http://$it:$PORT" }
    }

    private fun generatePin(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun enumerateLocalPairs(hasVpn: Boolean): List<Pair<String, String>> {
        val scored = mutableListOf<Triple<Int, String, String>>()
        try {
            for (intf in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val isTunnel = name.startsWith("tun") || name.startsWith("tap")
                if (isTunnel && !hasVpn) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                    val host = addr.hostAddress ?: continue
                    scored.add(Triple(interfaceRank(name), name, host))
                }
            }
        } catch (_: Exception) { }
        return scored
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .map { it.second to it.third }
            .distinct()
    }

    private fun interfaceRank(ifaceName: String): Int = when {
        ifaceName.startsWith("wlan") -> 0
        ifaceName.startsWith("eth") -> 1
        ifaceName.startsWith("tun") || ifaceName.startsWith("tap") -> 3
        else -> 2
    }
}
