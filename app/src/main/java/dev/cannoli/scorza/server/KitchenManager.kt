package dev.cannoli.scorza.server

import android.content.res.AssetManager
import java.io.File

object KitchenManager {

    private var server: FileServer? = null

    val isRunning: Boolean get() = server?.isRunning ?: false
    val pin: String get() = server?.pin ?: ""

    fun toggle(cannoliRoot: File, assets: AssetManager) {
        val s = server
        if (s != null && s.isRunning) {
            s.stop()
        } else {
            val newServer = FileServer(cannoliRoot, assets)
            server = newServer
            newServer.start()
        }
    }

    fun stop() {
        server?.stop()
    }

    fun getUrl(): String {
        val ip = getWifiIp()
        return "http://$ip:1091"
    }

    private fun getWifiIp(): String {
        try {
            for (intf in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                    return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) { }
        return "?.?.?.?"
    }
}
