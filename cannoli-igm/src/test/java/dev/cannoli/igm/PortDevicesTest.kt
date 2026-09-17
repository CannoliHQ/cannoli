package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortDevicesTest {

    private val none = PortDeviceType(0, "None")
    private val retroPad = PortDeviceType(1, "RetroPad")
    private val dualShock = PortDeviceType(261, "DualShock")

    private fun slot(player: Int, name: String?) = PlayerSlot(player, player, name, 0)

    @Test fun `none is never offered`() {
        assertEquals(listOf(1, 261), PortDevices(1, listOf(none, retroPad, dualShock)).choices.map { it.id })
    }

    @Test fun `a port offering only RetroPad has no choice`() {
        assertFalse(PortDevices(1, listOf(none, retroPad)).hasChoice)
        assertTrue(PortDevices(1, listOf(none, retroPad, dualShock)).hasChoice)
    }

    @Test fun `a type the port does not list is labelled by its id`() {
        val port = PortDevices(1, listOf(none, retroPad))
        assertEquals("RetroPad", port.labelFor(1))
        assertEquals("517", port.labelFor(517))
    }

    @Test fun `keys round trip for players 1 to 4 only`() {
        assertEquals("input_libretro_device_p1", PortDevices.keyFor(0))
        assertEquals(1, PortDevices.portFor("input_libretro_device_p2"))
        assertNull(PortDevices.portFor("input_libretro_device_p5"))
        assertNull(PortDevices.portFor("rewind_enable"))
    }

    @Test fun `two players with pads can swap`() {
        assertTrue(swapAllowed(listOf(slot(0, "Pad A"), slot(1, "Pad B")), 0, 1))
    }

    @Test fun `player 1 is never left without a pad`() {
        val slots = listOf(slot(0, "Pad A"), slot(1, null))
        assertFalse(swapAllowed(slots, 0, 1))
        assertFalse(swapAllowed(slots, 1, 0))
    }

    @Test fun `a pad can move to an empty player other than player 1`() {
        assertTrue(swapAllowed(listOf(slot(0, "Pad A"), slot(1, "Pad B"), slot(2, null)), 1, 2))
    }

    @Test fun `a row cannot swap with itself`() {
        assertFalse(swapAllowed(listOf(slot(0, "Pad A"), slot(1, "Pad B")), 1, 1))
    }

    @Test fun `swapping exchanges pads and keeps each player in place`() {
        val slots = listOf(PlayerSlot(0, 0, "Pad A", 0), PlayerSlot(1, 1, "Pad B", 2))
        assertEquals(
            listOf(PlayerSlot(0, 1, "Pad B", 2), PlayerSlot(1, 0, "Pad A", 0)),
            slots.swapped(0, 1),
        )
    }
}
