package dev.cannoli.scorza.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {

    // Deliberately not a RomM item: the queue schedules, dedupes and claims without knowing what
    // it is transferring, and a test that needed a RommGame to say so would be testing the wrong
    // thing.
    private fun item(id: Int, kind: DownloadKind = DownloadKind.ROM) = DownloadItem(
        key = "${kind.name}-$id",
        displayName = "Item $id",
        kind = kind,
        sizeBytes = 1L,
        tag = "SNES",
    )

    @Test fun `enqueue adds queued items and dedupes by key`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        q.enqueue(listOf(item(2), item(3)))
        assertEquals(listOf("ROM-1", "ROM-2", "ROM-3"), q.state.value.map { it.key })
        assertTrue(q.state.value.all { it.status == DownloadStatus.Queued })
    }

    @Test fun `re-enqueuing a completed key replaces it with a fresh queued item`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1)))
        q.setStatus("ROM-1", DownloadStatus.Done)
        q.enqueue(listOf(item(1)))
        assertEquals(1, q.state.value.size)
        assertEquals(DownloadStatus.Queued, q.state.value.single().status)
    }

    @Test fun `re-enqueuing an active key is ignored`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(5, 100))
        q.enqueue(listOf(item(1)))
        assertEquals(1, q.state.value.size)
        assertTrue(q.state.value.single().status is DownloadStatus.Downloading)
    }

    @Test fun `the same id under two kinds is two items`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(1, DownloadKind.MANUAL)))
        assertEquals(2, q.state.value.size)
        assertEquals(listOf("ROM-1", "MANUAL-1"), q.state.value.map { it.key })
    }

    @Test fun `claimNext takes first queued, marks it downloading, and never returns it twice`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        assertEquals("ROM-1", q.claimNext(setOf(DownloadKind.ROM))?.key)
        assertTrue(q.state.value.first { it.key == "ROM-1" }.status is DownloadStatus.Downloading)
        assertEquals("ROM-2", q.claimNext(setOf(DownloadKind.ROM))?.key)
        assertNull(q.claimNext(setOf(DownloadKind.ROM)))
    }

    @Test fun `cancel removes a queued item, cancelAll clears queued only`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(5, 100))
        q.cancel("ROM-2")
        assertEquals(listOf("ROM-1", "ROM-3"), q.state.value.map { it.key })
        q.cancelAll()
        assertEquals(listOf("ROM-1"), q.state.value.map { it.key }) // active stays; queued cleared
    }

    @Test fun `retry moves a failed item back to queued`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1)))
        q.setStatus("ROM-1", DownloadStatus.Failed("boom"))
        q.retry("ROM-1")
        assertEquals(DownloadStatus.Queued, q.state.value.single().status)
    }

    @Test fun `activeCount counts queued and downloading`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(0, 1))
        q.setStatus("ROM-2", DownloadStatus.Done)
        assertEquals(2, q.activeCount()) // downloading(1) + queued(3)
    }

    @Test fun `clearFinished removes done and failed but keeps queued and downloading`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3), item(4)))
        q.setStatus("ROM-1", DownloadStatus.Done)
        q.setStatus("ROM-2", DownloadStatus.Failed("x"))
        q.setStatus("ROM-3", DownloadStatus.Downloading(0, 1))
        q.clearFinished()
        assertEquals(listOf("ROM-3", "ROM-4"), q.state.value.map { it.key })
    }


    @Test fun `display order puts newest active first then completed in their own section`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3), item(4)))
        q.setStatus("ROM-1", DownloadStatus.Done)
        q.setStatus("ROM-3", DownloadStatus.Done)
        // Insertion order is 1,2,3,4. Active (2,4) newest-first, then done (1,3) newest-first.
        assertEquals(
            listOf("ROM-4", "ROM-2", "ROM-3", "ROM-1"),
            q.state.value.inDisplayOrder().map { it.key },
        )
    }

    // Lanes are the reason a core install does not wait behind a rom. A worker asks only for the
    // kinds it serves, so the rom sitting first in the list must not be what a core worker gets.
    @Test fun `a claim only takes the kinds its lane serves`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1, DownloadKind.ROM), item(2, DownloadKind.CORE)))
        assertEquals("CORE-2", q.claimNext(setOf(DownloadKind.CORE))?.key)
        assertEquals("ROM-1", q.claimNext(setOf(DownloadKind.ROM))?.key)
    }

    @Test fun `a lane with nothing of its kind claims nothing rather than someone else's work`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1, DownloadKind.ROM)))
        assertNull(q.claimNext(setOf(DownloadKind.CORE)))
        assertEquals("ROM-1", q.claimNext(setOf(DownloadKind.ROM))?.key)
    }

    @Test fun `the active count can be asked about one lane`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1, DownloadKind.ROM), item(2, DownloadKind.ROM), item(3, DownloadKind.CORE)))
        assertEquals(3, q.activeCount())
        assertEquals(1, q.activeCount(setOf(DownloadKind.CORE)))
        assertEquals(2, q.activeCount(setOf(DownloadKind.ROM)))
    }

    // Cancelling is about work still to come. Finished rows are the record of what happened and
    // only Clear Finished removes them, so a cancel-all must not sweep them up as collateral.
    @Test fun `cancelAll drops what has not started and keeps the rest`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(5, 100))
        q.setStatus("ROM-2", DownloadStatus.Done)
        q.cancelAll()
        assertEquals(listOf("ROM-1", "ROM-2"), q.state.value.map { it.key })
    }

    @Test fun `a failed row also survives cancelAll`() {
        val q = DownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        q.setStatus("ROM-1", DownloadStatus.Failed("nope"))
        q.cancelAll()
        assertEquals(listOf("ROM-1"), q.state.value.map { it.key })
    }
}
