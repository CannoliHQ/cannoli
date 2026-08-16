package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncSchedulerTest {
    // Counting conflicts opens the save database. A user who never configured RomM would otherwise
    // have it created on every network drop, because Kotlin evaluates the argument either way.
    @Test fun `the conflict count is not queried while sync is off`() {
        assertEquals(0, SyncScheduler.conflictsToReport(enabled = false) { fail("must not query"); 0 })
    }

    @Test fun `the conflict count is reported while sync is on`() {
        assertEquals(3, SyncScheduler.conflictsToReport(enabled = true) { 3 })
    }

    @Test fun `debounce blocks within interval and allows after`() {
        assertFalse(SyncScheduler.shouldSweep(now = 1_000L, lastSweepAt = 900L, intervalMs = 1_800_000L))
        assertTrue(SyncScheduler.shouldSweep(now = 2_000_000L, lastSweepAt = 100L, intervalMs = 1_800_000L))
    }

    // Screen lock/unlock re-registers the network callbacks, which fire immediately: without a
    // cooldown every unlock forces a full sweep, several times over.
    @Test fun `forced sweeps are held off until the cooldown passes`() {
        assertFalse(SyncScheduler.pastForceCooldown(now = 130_000L, lastSweepAt = 100_000L, cooldownMs = 60_000L))
        assertTrue(SyncScheduler.pastForceCooldown(now = 160_000L, lastSweepAt = 100_000L, cooldownMs = 60_000L))
    }

    @Test fun `the first forced sweep is never held off`() {
        assertTrue(SyncScheduler.pastForceCooldown(now = 1_000L, lastSweepAt = 0L, cooldownMs = 60_000L))
    }
}
