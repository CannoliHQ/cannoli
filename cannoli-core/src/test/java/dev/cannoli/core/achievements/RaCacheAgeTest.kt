package dev.cannoli.core.achievements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaCacheAgeTest {

    private val day = 24L * 60 * 60 * 1000

    @Test fun `a set cached today is fresh`() {
        assertFalse(RaCacheAge.isStale(cachedAtMs = 1000 * day, nowMs = 1000 * day))
    }

    @Test fun `a set cached a month ago is stale`() {
        assertTrue(RaCacheAge.isStale(cachedAtMs = 1000 * day, nowMs = 1031 * day))
    }

    @Test fun `the boundary is thirty days, and thirty is not yet stale`() {
        assertFalse(RaCacheAge.isStale(cachedAtMs = 1000 * day, nowMs = 1030 * day))
        assertTrue(RaCacheAge.isStale(cachedAtMs = 1000 * day, nowMs = 1030 * day + 1))
    }

    @Test fun `a clock that went backwards is not stale`() {
        assertFalse(RaCacheAge.isStale(cachedAtMs = 1000 * day, nowMs = 900 * day))
    }
}
