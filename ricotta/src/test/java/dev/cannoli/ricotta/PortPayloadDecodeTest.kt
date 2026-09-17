package dev.cannoli.ricotta

import dev.cannoli.igm.PlayerSlot
import dev.cannoli.igm.PortDeviceType
import dev.cannoli.igm.PortDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the pair layout nativePortDeviceTypes and nativePlayers build with ricotta_fields_to_array. */
class PortPayloadDecodeTest {

    @Test
    fun `decodes the current type and every listed type`() {
        assertEquals(
            PortDevices(261, listOf(PortDeviceType(0, "None"), PortDeviceType(1, "RetroPad"), PortDeviceType(261, "DualShock"))),
            EmbeddedRetroArchBridge.decodePortDevices(
                arrayOf("current", "261", "0", "None", "1", "RetroPad", "261", "DualShock"),
            ),
        )
    }

    @Test
    fun `no payload is no port`() {
        assertNull(EmbeddedRetroArchBridge.decodePortDevices(null))
        assertNull(EmbeddedRetroArchBridge.decodePortDevices(emptyArray()))
    }

    @Test
    fun `a type with an unreadable id is dropped`() {
        assertEquals(
            PortDevices(1, listOf(PortDeviceType(1, "RetroPad"))),
            EmbeddedRetroArchBridge.decodePortDevices(arrayOf("current", "1", "x", "Broken", "1", "RetroPad")),
        )
    }

    @Test
    fun `decodes players in order, an empty name meaning no pad`() {
        assertEquals(
            listOf(
                PlayerSlot(0, 0, "8BitDo SN30 Pro", 1),
                PlayerSlot(1, 1, "8BitDo SN30 Pro", 2),
                PlayerSlot(2, 2, null, 0),
            ),
            EmbeddedRetroArchBridge.decodePlayers(
                arrayOf(
                    "pad", "0", "set", "1", "name", "8BitDo SN30 Pro",
                    "pad", "1", "set", "2", "name", "8BitDo SN30 Pro",
                    "pad", "2", "set", "0", "name", "",
                ),
            ),
        )
    }

    @Test
    fun `no player payload is no players`() {
        assertEquals(emptyList<PlayerSlot>(), EmbeddedRetroArchBridge.decodePlayers(null))
    }
}
