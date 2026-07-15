package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommArtAvailabilityTest {

    @Test fun `a stock RomM offers only cover and screenshot art`() {
        val types = availableArtTypes(setOf("box2d", "screenshot", "manual"))
        assertEquals(
            listOf(RommArtType.NONE, RommArtType.DEFAULT, RommArtType.BOX2D, RommArtType.SCREENSHOT),
            types,
        )
        assertFalse(RommArtType.BOX3D in types)
        assertFalse(RommArtType.MARQUEE in types)
    }

    @Test fun `an instance that scrapes everything offers every art type`() {
        val types = availableArtTypes(
            setOf("box2d", "box3d", "miximage", "title_screen", "screenshot", "marquee"),
        )
        assertEquals(RommArtType.entries, types)
    }

    @Test fun `an unknown media set does not restrict, so a failed config fetch hides nothing`() {
        assertEquals(RommArtType.entries, availableArtTypes(emptySet()))
    }

    @Test fun `Off is always offered`() {
        assertTrue(RommArtType.NONE in availableArtTypes(setOf("box2d")))
        assertTrue(RommArtType.NONE in availableArtTypes(emptySet()))
    }

    @Test fun `each art type maps to the SCAN_MEDIA key that backs it`() {
        assertEquals(null, RommArtType.NONE.mediaKey())
        assertEquals("box2d", RommArtType.DEFAULT.mediaKey())
        assertEquals("box2d", RommArtType.BOX2D.mediaKey())
        assertEquals("box3d", RommArtType.BOX3D.mediaKey())
        assertEquals("miximage", RommArtType.MIX.mediaKey())
        assertEquals("title_screen", RommArtType.TITLE.mediaKey())
        assertEquals("screenshot", RommArtType.SCREENSHOT.mediaKey())
        assertEquals("marquee", RommArtType.MARQUEE.mediaKey())
    }
}
