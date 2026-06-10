package dev.cannoli.scorza.romm.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommSyncPlannerTest {

    @Test fun `nextCursor is the lexicographic max of current and ingested non-nulls`() {
        val next = RommSyncPlanner.nextCursor(
            current = "2024-01-01T00:00:00",
            ingested = listOf("2024-03-03T00:00:00", null, "2024-02-02T00:00:00"),
        )
        assertEquals("2024-03-03T00:00:00", next)
    }

    @Test fun `nextCursor keeps current when nothing newer ingested`() {
        assertEquals("2024-05-05T00:00:00", RommSyncPlanner.nextCursor("2024-05-05T00:00:00", listOf(null, "2024-01-01T00:00:00")))
    }

    @Test fun `nextCursor is null when nothing is known`() {
        assertNull(RommSyncPlanner.nextCursor(null, listOf(null, null)))
    }

    @Test fun `platformsNeedingReconcile finds count mismatches and missing platforms`() {
        val cached = mapOf(1 to 10, 2 to 5, 3 to 0)
        val server = mapOf(1 to 10, 2 to 7, 3 to 0)
        // platform 2 differs (5 != 7); 1 and 3 match.
        assertEquals(listOf(2), RommSyncPlanner.platformsNeedingReconcile(cached, server))
    }

    @Test fun `platformsNeedingReconcile treats absent cached count as zero`() {
        val cached = mapOf(1 to 0)
        val server = mapOf(1 to 0, 2 to 3)
        assertEquals(listOf(2), RommSyncPlanner.platformsNeedingReconcile(cached, server))
    }

    @Test fun `staleIds are cached ids not seen in the re-pull`() {
        assertEquals(setOf(2, 4), RommSyncPlanner.staleIds(cached = setOf(1, 2, 3, 4), seen = setOf(1, 3)))
    }
}
