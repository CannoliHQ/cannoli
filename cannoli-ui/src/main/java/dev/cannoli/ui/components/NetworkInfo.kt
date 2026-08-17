package dev.cannoli.ui.components

enum class InterfaceKind { WIFI, ETHERNET, VPN, OTHER }

data class NetworkEndpoint(val kind: InterfaceKind, val ip: String, val kitchenUrl: String)

data class RommStatus(val host: String, val reachable: Boolean)

data class NetworkInfoLabels(
    val interfaceLabel: String,
    val ip: String,
    val kitchen: String,
    val pin: String,
    val romm: String,
    val wifi: String,
    val ethernet: String,
    val vpn: String,
    val other: String,
    val notConnected: String,
    val notRunning: String,
    val unreachable: String,
    val dash: String,
)

fun interfaceKindFor(ifaceName: String): InterfaceKind {
    val n = ifaceName.lowercase()
    return when {
        n.startsWith("wlan") -> InterfaceKind.WIFI
        n.startsWith("eth") -> InterfaceKind.ETHERNET
        n.startsWith("tun") || n.startsWith("tap") -> InterfaceKind.VPN
        else -> InterfaceKind.OTHER
    }
}

object NetworkInfoRows {
    fun build(
        endpoints: List<NetworkEndpoint>,
        kitchenRunning: Boolean,
        pin: String?,
        romm: RommStatus?,
        selectedIndex: Int,
        labels: NetworkInfoLabels,
    ): List<InfoRowItem> {
        val ep = endpoints.getOrNull(selectedIndex.coerceIn(0, (endpoints.size - 1).coerceAtLeast(0)))
        val rows = mutableListOf<InfoRowItem>()

        rows += if (ep == null) {
            InfoRowItem(labels.interfaceLabel, labels.notConnected, muted = true)
        } else {
            InfoRowItem(labels.interfaceLabel, kindLabel(ep.kind, labels))
        }

        rows += if (ep == null) {
            InfoRowItem(labels.ip, labels.dash, muted = true)
        } else {
            InfoRowItem(labels.ip, ep.ip)
        }

        rows += if (kitchenRunning && ep != null) {
            InfoRowItem(labels.kitchen, ep.kitchenUrl)
        } else {
            InfoRowItem(labels.kitchen, labels.notRunning, muted = true)
        }

        if (pin != null && ep != null) rows += InfoRowItem(labels.pin, pin)

        if (romm != null) {
            rows += if (romm.reachable) {
                InfoRowItem(labels.romm, romm.host, status = InfoStatus.OK)
            } else {
                InfoRowItem(labels.romm, labels.unreachable, muted = true)
            }
        }
        return rows
    }

    private fun kindLabel(kind: InterfaceKind, labels: NetworkInfoLabels): String = when (kind) {
        InterfaceKind.WIFI -> labels.wifi
        InterfaceKind.ETHERNET -> labels.ethernet
        InterfaceKind.VPN -> labels.vpn
        InterfaceKind.OTHER -> labels.other
    }
}
