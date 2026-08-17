package dev.cannoli.scorza.ui

import dev.cannoli.ui.components.InfoStatus
import dev.cannoli.ui.components.InterfaceKind
import dev.cannoli.ui.components.NetworkEndpoint
import dev.cannoli.ui.components.NetworkInfoLabels
import dev.cannoli.ui.components.NetworkInfoRows
import dev.cannoli.ui.components.RommStatus
import dev.cannoli.ui.components.interfaceKindFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInfoRowsTest {
    private val labels = NetworkInfoLabels(
        interfaceLabel = "Interface", ip = "IP Address", kitchen = "Kitchen", pin = "PIN", romm = "RomM",
        wifi = "Wi-Fi", ethernet = "Ethernet", vpn = "VPN", other = "Other",
        notConnected = "Not connected", notRunning = "Not running", unreachable = "Unreachable", dash = "-",
    )
    private val wifi = NetworkEndpoint(InterfaceKind.WIFI, "192.168.1.42", "192.168.1.42:1091")

    @Test fun classifiesInterfaceNames() {
        assertEquals(InterfaceKind.WIFI, interfaceKindFor("wlan0"))
        assertEquals(InterfaceKind.ETHERNET, interfaceKindFor("eth0"))
        assertEquals(InterfaceKind.VPN, interfaceKindFor("tun0"))
        assertEquals(InterfaceKind.VPN, interfaceKindFor("tap0"))
        assertEquals(InterfaceKind.OTHER, interfaceKindFor("rmnet0"))
    }

    @Test fun offlineMutesEverythingAndDropsPin() {
        val rows = NetworkInfoRows.build(emptyList(), kitchenRunning = false, pin = "4821", romm = null, selectedIndex = 0, labels)
        assertEquals(listOf("Interface", "IP Address", "Kitchen"), rows.map { it.label })
        assertEquals(listOf("Not connected", "-", "Not running"), rows.map { it.value })
        assertEquals(listOf(true, true, true), rows.map { it.muted })
    }

    @Test fun onlineWithKitchenAndPin() {
        val rows = NetworkInfoRows.build(listOf(wifi), kitchenRunning = true, pin = "4821", romm = null, selectedIndex = 0, labels)
        assertEquals(listOf("Interface", "IP Address", "Kitchen", "PIN"), rows.map { it.label })
        assertEquals(listOf("Wi-Fi", "192.168.1.42", "192.168.1.42:1091", "4821"), rows.map { it.value })
        assertEquals(listOf(false, false, false, false), rows.map { it.muted })
    }

    @Test fun pinNullMeansNoPinRow() {
        val rows = NetworkInfoRows.build(listOf(wifi), kitchenRunning = true, pin = null, romm = null, selectedIndex = 0, labels)
        assertEquals(listOf("Interface", "IP Address", "Kitchen"), rows.map { it.label })
    }

    @Test fun kitchenOffMutesTheRow() {
        val rows = NetworkInfoRows.build(listOf(wifi), kitchenRunning = false, pin = null, romm = null, selectedIndex = 0, labels)
        val kitchen = rows.single { it.label == "Kitchen" }
        assertEquals("Not running", kitchen.value)
        assertEquals(true, kitchen.muted)
    }

    @Test fun rommReachableShowsHostWithOkStatus() {
        val rows = NetworkInfoRows.build(listOf(wifi), kitchenRunning = true, pin = null, romm = RommStatus("romm.home.net", true), selectedIndex = 0, labels)
        val romm = rows.single { it.label == "RomM" }
        assertEquals("romm.home.net", romm.value)
        assertEquals(false, romm.muted)
        assertEquals(InfoStatus.OK, romm.status)
    }

    @Test fun rommUnreachableIsMutedNoStatus() {
        val rows = NetworkInfoRows.build(listOf(wifi), kitchenRunning = true, pin = null, romm = RommStatus("romm.home.net", false), selectedIndex = 0, labels)
        val romm = rows.single { it.label == "RomM" }
        assertEquals("Unreachable", romm.value)
        assertEquals(true, romm.muted)
        assertNull(romm.status)
    }

    @Test fun selectedIndexPicksInterfaceAndClamps() {
        val eth = NetworkEndpoint(InterfaceKind.ETHERNET, "10.0.0.5", "10.0.0.5:1091")
        val rows = NetworkInfoRows.build(listOf(wifi, eth), kitchenRunning = true, pin = null, romm = null, selectedIndex = 9, labels)
        assertEquals("Ethernet", rows.first { it.label == "Interface" }.value)
        assertEquals("10.0.0.5", rows.first { it.label == "IP Address" }.value)
    }
}
