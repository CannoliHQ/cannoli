package dev.cannoli.scorza.server

import dev.cannoli.ui.components.InterfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitchenEndpointsTest {
    @Test fun mapsPairsToEndpointsWithKindAndUrl() {
        val eps = KitchenManager.endpointsFrom(listOf("wlan0" to "192.168.1.42", "tun0" to "10.8.0.2"))
        assertEquals(listOf(InterfaceKind.WIFI, InterfaceKind.VPN), eps.map { it.kind })
        assertEquals(listOf("192.168.1.42", "10.8.0.2"), eps.map { it.ip })
        assertEquals("192.168.1.42:1091", eps[0].kitchenUrl)
    }

    @Test fun pinShowsOnlyWhenRunningNotBypassedAndSet() {
        assertEquals("4821", KitchenManager.pinForDisplayValue(running = true, codeBypass = false, pin = "4821"))
        assertNull(KitchenManager.pinForDisplayValue(running = false, codeBypass = false, pin = "4821"))
        assertNull(KitchenManager.pinForDisplayValue(running = true, codeBypass = true, pin = "4821"))
        assertNull(KitchenManager.pinForDisplayValue(running = true, codeBypass = false, pin = ""))
    }
}
