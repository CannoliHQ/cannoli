package dev.cannoli.scorza.input.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAliasesTest {

    @Test fun `parses a pipe separated list`() {
        assertEquals(
            listOf("GameSir-Pocket 1 Keyboard", "GameSir-Pocket 1 Consumer Control"),
            DeviceAliases.parse("GameSir-Pocket 1 Keyboard|GameSir-Pocket 1 Consumer Control"),
        )
    }

    @Test fun `trims members and drops blanks`() {
        assertEquals(listOf("Pad A", "Pad B"), DeviceAliases.parse(" Pad A | |Pad B "))
    }

    @Test fun `an empty value parses as no aliases`() {
        assertEquals(emptyList<String>(), DeviceAliases.parse(""))
    }

    @Test fun `formats with a pipe`() {
        assertEquals("Pad A|Pad B", DeviceAliases.format(listOf("Pad A", "Pad B")))
    }
}
