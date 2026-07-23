package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommVirtualTypeTest {
    @Test fun `from maps known server values`() {
        assertEquals(RommVirtualType.FRANCHISE, RommVirtualType.from("franchise"))
        assertEquals(RommVirtualType.MODE, RommVirtualType.from("mode"))
        assertNull(RommVirtualType.from("nonsense"))
        assertNull(RommVirtualType.from(null))
    }

    @Test fun `orderedFrom puts known types in enum order then unknowns`() {
        val ordered = RommVirtualType.orderedFrom(listOf("genre", "mystery", "franchise"))
        assertEquals(listOf("franchise", "genre", "mystery"), ordered)
    }
}
