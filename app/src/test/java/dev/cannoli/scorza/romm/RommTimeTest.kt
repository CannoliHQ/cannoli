package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommTimeTest {

    /**
     * The form RomM actually sends. Python's datetime.isoformat() writes the offset in full, and
     * Instant.parse only accepts that from JDK 12, which Android 13 predates. Parsing it that way
     * passes on a desktop JVM and fails on the handheld, which is how a server timestamp the API
     * had sent correctly reached a conflict screen as "unknown".
     */
    @Test fun `the offset form RomM sends parses`() {
        assertEquals(1788790484123L, RommTime.millisOrNull("2026-09-07T14:14:44.123456+00:00"))
        assertEquals(1788790484000L, RommTime.millisOrNull("2026-09-07T14:14:44+00:00"))
    }

    /** Our own isoOf writes Instant.toString(), which is the Z form. Both have to work. */
    @Test fun `the Z form we write ourselves parses`() {
        assertEquals(1788790484123L, RommTime.millisOrNull("2026-09-07T14:14:44.123456Z"))
        assertEquals(1788790484000L, RommTime.millisOrNull("2026-09-07T14:14:44Z"))
    }

    /** A clock four hours behind UTC reads the same wall time at a later instant, not an earlier one. */
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

    /** Absent is not zero: a caller that cannot tell them apart shows the epoch as a real time. */
    @Test fun `nothing to parse is null`() {
        assertNull(RommTime.millisOrNull(null))
        assertNull(RommTime.millisOrNull(""))
        assertNull(RommTime.millisOrNull("   "))
        assertNull(RommTime.millisOrNull("not a timestamp"))
        assertNull(RommTime.millisOrNull("2026-09-07"))
    }
}
