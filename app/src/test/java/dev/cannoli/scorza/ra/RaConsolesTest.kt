package dev.cannoli.scorza.ra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaConsolesTest {
    @Test
    fun map_hasExpectedConsoleIds() {
        assertEquals(7, RaConsoles.MAP["NES"])
        assertEquals(3, RaConsoles.MAP["SNES"])
        assertEquals(12, RaConsoles.MAP["PS"])
        assertNull(RaConsoles.MAP["NOT_A_PLATFORM"])
    }
}
