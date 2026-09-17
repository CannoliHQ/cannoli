package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRemapTest {

    @Test fun `ids and key names are RetroArch's own`() {
        assertEquals(0, RemapButton.SOUTH.id)
        assertEquals("b", RemapButton.SOUTH.raKey)
        assertEquals(8, RemapButton.EAST.id)
        assertEquals("a", RemapButton.EAST.raKey)
        assertEquals(1, RemapButton.WEST.id)
        assertEquals("y", RemapButton.WEST.raKey)
        assertEquals(9, RemapButton.NORTH.id)
        assertEquals("x", RemapButton.NORTH.raKey)
        assertEquals(2, RemapButton.SELECT.id)
        assertEquals(15, RemapButton.R3.id)
    }

    @Test fun `rows are listed in pad order rather than id order`() {
        assertEquals(
            listOf(
                "up", "down", "left", "right",
                "b", "a", "y", "x",
                "l", "r", "l2", "r2", "l3", "r3",
                "start", "select",
            ),
            RemapButton.entries.map { it.raKey },
        )
    }

    @Test fun `the four face rows take their label from the pad, the rest from a string`() {
        assertNull(RemapButton.SOUTH.labelRes)
        assertNull(RemapButton.EAST.labelRes)
        assertNull(RemapButton.WEST.labelRes)
        assertNull(RemapButton.NORTH.labelRes)
        assertTrue(RemapButton.entries.filter { it.labelRes == null }.size == 4)
    }

    @Test fun `a tier key round trips`() {
        assertEquals("cannoli_remap_l2", ButtonRemap.keyFor(RemapButton.L2))
        assertEquals(RemapButton.L2, ButtonRemap.buttonForKey("cannoli_remap_l2"))
    }

    @Test fun `a key that is not a remap is not one`() {
        assertNull(ButtonRemap.buttonForKey("cannoli_overlay"))
        assertNull(ButtonRemap.buttonForKey("cannoli_remap_menu"))
        assertNull(ButtonRemap.buttonForKey("l2"))
    }

    @Test fun `only a real id or unbound is a value`() {
        assertEquals(0, ButtonRemap.valueOf("0"))
        assertEquals(15, ButtonRemap.valueOf("15"))
        assertEquals(-1, ButtonRemap.valueOf("-1"))
        assertNull(ButtonRemap.valueOf("16"))
        assertNull(ButtonRemap.valueOf("-2"))
        assertNull(ButtonRemap.valueOf("b"))
        assertNull(ButtonRemap.valueOf(""))
        assertNull(ButtonRemap.valueOf(null))
    }

    @Test fun `a button sends itself unless the map says otherwise`() {
        assertEquals(0, ButtonRemap.target(emptyMap(), RemapButton.SOUTH))
        assertEquals(8, ButtonRemap.target(mapOf(0 to 8), RemapButton.SOUTH))
        assertEquals(-1, ButtonRemap.target(mapOf(0 to -1), RemapButton.SOUTH))
    }

    @Test fun `default means every button still sends itself`() {
        assertTrue(ButtonRemap.isDefault(emptyMap()))
        assertTrue(ButtonRemap.isDefault(ButtonRemap.identity()))
        assertFalse(ButtonRemap.isDefault(mapOf(0 to 8)))
        assertFalse(ButtonRemap.isDefault(mapOf(0 to ButtonRemap.UNBOUND)))
    }

    @Test fun `identity names every button once`() {
        assertEquals(16, ButtonRemap.identity().size)
        assertEquals(RemapButton.entries.map { it.id }.toSet(), ButtonRemap.identity().keys)
    }

    @Test fun `a position names the button sitting there`() {
        assertEquals(RemapButton.SOUTH, RemapButton.forPosition(CanonicalButton.BTN_SOUTH))
        assertEquals(RemapButton.L3, RemapButton.forPosition(CanonicalButton.BTN_L3))
        assertNull(RemapButton.forPosition(CanonicalButton.BTN_MENU))
        assertNull(RemapButton.forPosition(CanonicalButton.BTN_LSTICK_X))
    }

    @Test fun `an id names its button`() {
        assertEquals(RemapButton.START, RemapButton.forId(3))
        assertNull(RemapButton.forId(16))
        assertNull(RemapButton.forId(-1))
    }
}
