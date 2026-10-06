package dev.cannoli.scorza.achievements

import org.junit.Assert.assertNull
import org.junit.Test

class RaHasherTest {
    @Test fun `answers null rather than throwing when the library is missing`() {
        assertNull(RaHasher.hashRom("/roms/nes/Hack.nes", 7))
    }
}
