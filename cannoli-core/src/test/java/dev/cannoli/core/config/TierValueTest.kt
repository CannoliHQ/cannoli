package dev.cannoli.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TierValueTest {

    @Test fun `a missing key means no opinion and an empty one means deliberately nothing`() {
        assertEquals(TierValue.Inherit, TierValue.of(null))
        assertEquals(TierValue.Off, TierValue.of(""))
        assertEquals(TierValue.Off, TierValue.of("   "))
        assertEquals(TierValue.Set("crt.slangp"), TierValue.of("crt.slangp"))
    }

    @Test fun `only inheriting removes the key`() {
        assertNull(TierValue.serialise(TierValue.Inherit))
        assertEquals("", TierValue.serialise(TierValue.Off))
        assertEquals("crt.slangp", TierValue.serialise(TierValue.Set("crt.slangp")))
    }

    @Test fun `every state survives a round trip through the file`() {
        for (value in listOf(TierValue.Inherit, TierValue.Off, TierValue.Set("x"))) {
            assertEquals(value, TierValue.of(TierValue.serialise(value)))
        }
    }

    @Test fun `only a chosen value reads as one`() {
        assertEquals("x", TierValue.Set("x").chosen)
        assertNull(TierValue.Off.chosen)
        assertNull(TierValue.Inherit.chosen)
    }
}
