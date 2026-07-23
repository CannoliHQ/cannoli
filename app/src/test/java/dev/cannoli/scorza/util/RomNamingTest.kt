package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomNamingTest {

    @Test fun `plain name has no tags`() {
        val (name, tags) = RomNaming.splitNameAndTags("Super Mario World")
        assertEquals("Super Mario World", name)
        assertNull(tags)
    }

    @Test fun `parenthetical region is split into tags`() {
        val (name, tags) = RomNaming.splitNameAndTags("Chrono Trigger (USA)")
        assertEquals("Chrono Trigger", name)
        assertEquals("(USA)", tags)
    }

    @Test fun `bracket and paren tags are both captured`() {
        val (name, tags) = RomNaming.splitNameAndTags("Zelda (USA) [!]")
        assertEquals("Zelda", name)
        assertEquals("(USA) [!]", tags)
    }

    @Test fun `name that is entirely a tag is kept verbatim`() {
        val (name, tags) = RomNaming.splitNameAndTags("(USA)")
        assertEquals("(USA)", name)
        assertNull(tags)
    }
}
