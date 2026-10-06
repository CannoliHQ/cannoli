package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommTimeTest {

    @Test fun `the offset form RomM sends parses`() {
        assertEquals(1788790484123L, RommTime.millisOrNull("2026-09-07T14:14:44.123456+00:00"))
        assertEquals(1788790484000L, RommTime.millisOrNull("2026-09-07T14:14:44+00:00"))
    }

    @Test fun `the Z form we write ourselves parses`() {
        assertEquals(1788790484123L, RommTime.millisOrNull("2026-09-07T14:14:44.123456Z"))
        assertEquals(1788790484000L, RommTime.millisOrNull("2026-09-07T14:14:44Z"))
    }

    @Test fun `a non-UTC offset is applied in the right direction`() {
        assertEquals(
            RommTime.millisOrNull("2026-09-07T14:14:44+00:00")!! + 4 * 60 * 60 * 1000L,
            RommTime.millisOrNull("2026-09-07T14:14:44-04:00"),
        )
        assertEquals(
            RommTime.millisOrNull("2026-09-07T18:14:44Z"),
            RommTime.millisOrNull("2026-09-07T14:14:44-04:00"),
        )
    }

    @Test fun `nothing to parse is null`() {
        assertNull(RommTime.millisOrNull(null))
        assertNull(RommTime.millisOrNull(""))
        assertNull(RommTime.millisOrNull("   "))
        assertNull(RommTime.millisOrNull("not a timestamp"))
        assertNull(RommTime.millisOrNull("2026-09-07"))
    }
}
