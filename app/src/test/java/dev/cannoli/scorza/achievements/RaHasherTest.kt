package dev.cannoli.scorza.achievements

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * There is no native library under JVM unit tests, which is exactly the case this pins. Loading it
 * from an init block threw at the call site rather than returning null, and that escaped as a
 * generic failure which the user was shown as a network problem.
 */
class RaHasherTest {
    @Test fun `answers null rather than throwing when the library is missing`() {
        assertNull(RaHasher.hashRom("/roms/nes/Hack.nes", 7))
    }
}
