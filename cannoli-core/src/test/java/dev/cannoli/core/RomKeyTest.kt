package dev.cannoli.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RomKeyTest {

    @Test fun `strips the extension`() {
        assertEquals("Super Game", RomKey.baseName(File("/roms/NES/Super Game.nes")))
    }

    @Test fun `normalizes a non-precomposed filename to its precomposed form`() {
        // "e" (U+0065) followed by a combining acute accent (U+0301) is NFD; the single
        // "e with acute" codepoint (U+00E9) is NFC.
        val decomposed = "Poke\u0301mon Red (USA)"
        val precomposed = "Pok\u00e9mon Red (USA)"
        assertEquals(precomposed, RomKey.baseName(File("/roms/GB/$decomposed.gb")))
    }

    @Test fun `a bundled multi-disc path already at m3u level yields the bundle name`() {
        assertEquals(
            "Final Fantasy VII",
            RomKey.baseName(File("/roms/PSX/Final Fantasy VII/Final Fantasy VII.m3u")),
        )
    }

    @Test fun `a precomposed filename is left unchanged`() {
        val precomposed = "Pok\u00e9mon Red (USA)"
        assertEquals(precomposed, RomKey.baseName(File("/roms/GB/$precomposed.gb")))
    }
}
