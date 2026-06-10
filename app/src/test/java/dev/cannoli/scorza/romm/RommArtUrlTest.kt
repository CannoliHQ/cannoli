package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommArtUrlTest {

    @Test fun `absolute url passes through`() {
        assertEquals("https://cdn.example/cover.png",
            RommArtUrl.resolve("https://romm.example.com", "https://cdn.example/cover.png"))
    }

    @Test fun `relative path joined to host with single slash`() {
        assertEquals("https://romm.example.com/assets/roms/snes/1/cover.png",
            RommArtUrl.resolve("https://romm.example.com/", "assets/roms/snes/1/cover.png"))
        assertEquals("https://romm.example.com/assets/x.png",
            RommArtUrl.resolve("https://romm.example.com", "/assets/x.png"))
    }

    @Test fun `null or blank cover returns null`() {
        assertNull(RommArtUrl.resolve("https://romm.example.com", null))
        assertNull(RommArtUrl.resolve("https://romm.example.com", "  "))
    }
}
