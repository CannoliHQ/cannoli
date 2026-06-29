package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    @Test fun `debounce blocks within interval and allows after`() {
        assertFalse(SyncScheduler.shouldSweep(now = 1_000L, lastSweepAt = 900L, intervalMs = 1_800_000L))
        assertTrue(SyncScheduler.shouldSweep(now = 2_000_000L, lastSweepAt = 100L, intervalMs = 1_800_000L))
    }
}
