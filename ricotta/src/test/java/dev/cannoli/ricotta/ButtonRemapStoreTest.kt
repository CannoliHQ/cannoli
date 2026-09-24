package dev.cannoli.ricotta

import dev.cannoli.igm.ButtonRemap
import dev.cannoli.igm.RemapButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `an unset button falls back to the routed base, not to itself`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = emptyMap(), system = emptyMap(),
            base = mapOf(RemapButton.EAST.id to RemapButton.WEST.id),
        )
        assertEquals(RemapButton.WEST.id, remap[RemapButton.EAST.id])
        assertEquals(RemapButton.SOUTH.id, remap[RemapButton.SOUTH.id])
    }

    @Test fun `a row the user set beats the routed base, and the rest keep it`() {
        val remap = EmbeddedRetroArchBridge.remapFromTiers(
            game = mapOf(key(RemapButton.L3) to "9"),
            system = emptyMap(),
            base = mapOf(RemapButton.L3.id to RemapButton.R.id, RemapButton.EAST.id to RemapButton.SOUTH.id),
        )
        assertEquals(9, remap[RemapButton.L3.id])
        assertEquals(RemapButton.SOUTH.id, remap[RemapButton.EAST.id])
    }

    @Test fun `old explicit identity keys outrank routing until a reset removes them`() {
        val identityKeys = RemapButton.entries.associate { key(it) to it.id.toString() }
        val base = mapOf(RemapButton.EAST.id to RemapButton.WEST.id)
        val before = EmbeddedRetroArchBridge.remapFromTiers(identityKeys, emptyMap(), base)
        assertEquals(RemapButton.EAST.id, before[RemapButton.EAST.id])

        val staged = EmbeddedRetroArchBridge.stagedRemapTierValues(
            RemapButton.entries.associate { it.id to ButtonRemap.INHERIT },
        )
        assertTrue(staged.values.all { it == dev.cannoli.core.config.TierValue.Inherit })
        assertEquals(16, staged.size)
    }

    @Test fun `a staged target is written as a value`() {
        val staged = EmbeddedRetroArchBridge.stagedRemapTierValues(mapOf(RemapButton.SOUTH.id to 8))
        assertEquals(dev.cannoli.core.config.TierValue.Set("8"), staged[key(RemapButton.SOUTH)])
    }

    @Test fun `a save that writes a remap key needs the pad resynced`() {
        val values = mapOf(key(RemapButton.SOUTH) to dev.cannoli.core.config.TierValue.Inherit)
        assertTrue(EmbeddedRetroArchBridge.writesRemap(values))
    }

    @Test fun `a save touching no remap key needs no resync`() {
        val values = mapOf("cannoli_overlay" to dev.cannoli.core.config.TierValue.Set("bezel"))
        assertFalse(EmbeddedRetroArchBridge.writesRemap(values))
    }

    @Test fun `an empty save needs no resync`() {
        assertFalse(EmbeddedRetroArchBridge.writesRemap(emptyMap()))
    }

    @Test fun `a reset with no routing stages every row as its own id`() {
        val staged = EmbeddedRetroArchBridge.resetRemapStaging(emptyMap())
        assertEquals(ButtonRemap.identity(), staged)
    }

    @Test fun `a reset with routing stages inherit only for the routed keys`() {
        val routed = mapOf(RemapButton.EAST.id to RemapButton.WEST.id)
        val staged = EmbeddedRetroArchBridge.resetRemapStaging(routed)
        assertEquals(ButtonRemap.INHERIT, staged[RemapButton.EAST.id])
        assertEquals(RemapButton.SOUTH.id, staged[RemapButton.SOUTH.id])
        assertEquals(RemapButton.entries.size, staged.size)
    }
}
