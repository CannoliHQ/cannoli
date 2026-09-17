package dev.cannoli.ricotta

import dev.cannoli.igm.ButtonRemap
import dev.cannoli.igm.RemapButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRemapStoreTest {

    private fun key(button: RemapButton) = ButtonRemap.keyFor(button)

    @Test fun `no tier mentions a button and it sends itself`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(emptyMap(), emptyMap())
        assertEquals(16, remap.size)
        assertTrue(ButtonRemap.isDefault(remap))
    }

    @Test fun `a platform remap shows through at game scope`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = emptyMap(),
            system = mapOf(key(RemapButton.SOUTH) to "8"),
        )
        assertEquals(8, remap[RemapButton.SOUTH.id])
        assertEquals(8, remap[RemapButton.EAST.id])
    }

    @Test fun `the game wins key by key`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = mapOf(key(RemapButton.SOUTH) to "9"),
            system = mapOf(key(RemapButton.SOUTH) to "8", key(RemapButton.L) to "11"),
        )
        assertEquals(9, remap[RemapButton.SOUTH.id])
        assertEquals(11, remap[RemapButton.L.id])
    }

    @Test fun `an identity entry at game scope beats a platform remap`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = mapOf(key(RemapButton.SOUTH) to "0"),
            system = mapOf(key(RemapButton.SOUTH) to "8"),
        )
        assertEquals(0, remap[RemapButton.SOUTH.id])
    }

    @Test fun `unbound is carried, not treated as missing`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = mapOf(key(RemapButton.L2) to "-1"),
            system = emptyMap(),
        )
        assertEquals(ButtonRemap.UNBOUND, remap[RemapButton.L2.id])
    }

    @Test fun `a value naming no button is ignored and the tier below answers`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = mapOf(key(RemapButton.SOUTH) to "99"),
            system = mapOf(key(RemapButton.SOUTH) to "8"),
        )
        assertEquals(8, remap[RemapButton.SOUTH.id])
    }

    @Test fun `only the buttons that moved are queued`() {
        val applied = mapOf(0 to 8, 1 to 1)
        val next = mapOf(0 to 8, 1 to 9)
        assertEquals(mapOf(1 to 9), EmbeddedRetroArchBridge.remapChanges(applied, next))
    }

    @Test fun `nothing moved queues nothing`() {
        val same = ButtonRemap.identity()
        assertTrue(EmbeddedRetroArchBridge.remapChanges(same, same).isEmpty())
    }

    @Test fun `a button never applied before counts as moved`() {
        assertEquals(mapOf(0 to 8), EmbeddedRetroArchBridge.remapChanges(emptyMap(), mapOf(0 to 8)))
    }
}
