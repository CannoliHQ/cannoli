package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonDescriptorsTest {

    @Test fun `pairs decode into names by RetroPad id`() {
        val names = EmbeddedRetroArchBridge.decodeDescriptors(arrayOf("8", "C", "1", "A", "11", "Z"))
        assertEquals(mapOf(8 to "C", 1 to "A", 11 to "Z"), names)
    }

    @Test fun `nothing from native means no names`() {
        assertTrue(EmbeddedRetroArchBridge.decodeDescriptors(null).isEmpty())
    }

    @Test fun `a blank name or a bad id is dropped`() {
        val names = EmbeddedRetroArchBridge.decodeDescriptors(arrayOf("8", "", "x", "B", "0", "B Button"))
        assertEquals(mapOf(0 to "B Button"), names)
    }
}
