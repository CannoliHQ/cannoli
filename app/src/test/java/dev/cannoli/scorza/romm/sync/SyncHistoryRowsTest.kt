package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.ui.screens.buildHistoryRows
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncHistoryRowsTest {

    @Test
    fun `relative time buckets`() {
        val now = 10_000_000L
        val rows = buildHistoryRows(listOf(
            SyncHistoryEntry("g", "A", SyncDirection.DOWNLOAD, null, now - 30_000),
            SyncHistoryEntry("g", "B", SyncDirection.UPLOAD, null, now - 5 * 60_000),
            SyncHistoryEntry("g", "C", SyncDirection.CONFLICT, null, now - 3 * 3600_000),
        ), now, "just now")
        assertEquals(listOf("just now", "5m", "3h"), rows.map { it.relativeTime })
        assertEquals(SyncDirection.CONFLICT, rows[2].direction)
    }

    @Test
    fun `day bucket`() {
        val now = 100_000_000L
        val rows = buildHistoryRows(listOf(
            SyncHistoryEntry("g", "X", SyncDirection.ERROR, null, now - 2 * 86_400_000),
        ), now, "just now")
        assertEquals("2d", rows[0].relativeTime)
    }

    @Test
    fun `zero delta shows just now`() {
        val now = 5_000_000L
        val rows = buildHistoryRows(listOf(
            SyncHistoryEntry("g", "Y", SyncDirection.UPLOAD, null, now),
        ), now, "just now")
        assertEquals("just now", rows[0].relativeTime)
    }

    @Test
    fun `display name is preserved`() {
        val now = 1_000_000L
        val entry = SyncHistoryEntry("key", "My Game", SyncDirection.DOWNLOAD, null, now - 10_000)
        val rows = buildHistoryRows(listOf(entry), now, "just now")
        assertEquals("My Game", rows[0].name)
        assertEquals(SyncDirection.DOWNLOAD, rows[0].direction)
    }

    @Test
    fun `detail is carried through`() {
        val now = 1_000L
        val rows = buildHistoryRows(listOf(
            SyncHistoryEntry("g", "E", SyncDirection.ERROR, "upload failed (403 Forbidden)", now),
        ), now, "just now")
        assertEquals("upload failed (403 Forbidden)", rows[0].detail)
    }

    @Test
    fun `exact day boundary`() {
        val now = 200_000_000L
        val rows = buildHistoryRows(listOf(
            SyncHistoryEntry("g", "Z", SyncDirection.UPLOAD, null, now - 86_400_000),
        ), now, "just now")
        assertEquals("1d", rows[0].relativeTime)
    }
}
