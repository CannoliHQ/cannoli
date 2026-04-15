package dev.cannoli.scorza.server

import android.content.res.AssetManager
import java.io.File

object KitchenManager {

    private var server: FileServer? = null

    val isRunning: Boolean get() = server?.isRunning ?: false
    val pin: String get() = server?.pin ?: ""

    fun toggle(cannoliRoot: File, assets: AssetManager, codeBypass: Boolean = false) {
        val s = server
        if (s != null && s.isRunning) {
            s.stop()
        } else {
            val newServer = FileServer(cannoliRoot, assets, codeBypass = codeBypass)
            server = newServer
            newServer.start()
        }
    }

    fun setCodeBypass(enabled: Boolean) {
        server?.codeBypass = enabled
    }

    fun stop() {
        server?.stop()
    }

    fun getUrls(hasVpn: Boolean): List<String> {
        val ips = enumerateLocalIps(hasVpn)
        if (ips.isEmpty()) return listOf("http://?.?.?.?:1091")
        return ips.map { "http://$it:1091" }
    }

    private fun enumerateLocalIps(hasVpn: Boolean): List<String> {
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
            .map { it.third }
            .distinct()
    }

    private fun interfaceRank(ifaceName: String): Int = when {
        ifaceName.startsWith("wlan") -> 0
        ifaceName.startsWith("eth") -> 1
        ifaceName.startsWith("tun") || ifaceName.startsWith("tap") -> 3
        else -> 2
    }
}
