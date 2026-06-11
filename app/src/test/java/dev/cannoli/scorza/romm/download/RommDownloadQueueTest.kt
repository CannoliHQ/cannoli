package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommDownloadQueueTest {

    private fun game(id: Int) = RommGame(id, 1, "Game $id", "g$id.sfc", 1L, null, null, emptyList(), emptyList(), null, emptyList())
    private fun item(id: Int) = RommDownloadItem(id, game(id), "SNES")

    @Test fun `enqueue adds queued items and dedupes by key`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        q.enqueue(listOf(item(2), item(3)))
        assertEquals(listOf(1, 2, 3), q.state.value.map { it.rommId })
        assertTrue(q.state.value.all { it.status == DownloadStatus.Queued })
    }

    @Test fun `rom and manual with same rommId coexist as distinct keys`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(1).copy(kind = RommDownloadKind.MANUAL)))
        assertEquals(2, q.state.value.size)
        assertEquals(listOf("ROM-1", "MANUAL-1"), q.state.value.map { it.key })
    }

    @Test fun `claimNext takes first queued, marks it downloading, and never returns it twice`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        assertEquals(1, q.claimNext()?.rommId)
        assertTrue(q.state.value.first { it.rommId == 1 }.status is DownloadStatus.Downloading)
        assertEquals(2, q.claimNext()?.rommId)
        assertNull(q.claimNext())
    }

    @Test fun `nextQueued returns first queued and status transitions`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2)))
        assertEquals(1, q.nextQueued()?.rommId)
        q.setStatus("ROM-1", DownloadStatus.Downloading(10, 100))
        assertEquals(2, q.nextQueued()?.rommId)
        q.setStatus("ROM-1", DownloadStatus.Done)
        q.setStatus("ROM-2", DownloadStatus.Downloading(0, 50))
        assertNull(q.nextQueued())
    }

    @Test fun `cancel removes a queued item, cancelAll clears queued only`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(5, 100))
        q.cancel("ROM-2")
        assertEquals(listOf(1, 3), q.state.value.map { it.rommId })
        q.cancelAll()
        assertEquals(listOf(1), q.state.value.map { it.rommId }) // active (1) stays; queued cleared
    }

    @Test fun `retry moves a failed item back to queued`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1)))
        q.setStatus("ROM-1", DownloadStatus.Failed("boom"))
        q.retry("ROM-1")
        assertEquals(DownloadStatus.Queued, q.state.value.single().status)
    }

    @Test fun `activeCount counts queued and downloading`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3)))
        q.setStatus("ROM-1", DownloadStatus.Downloading(0, 1))
        q.setStatus("ROM-2", DownloadStatus.Done)
        assertEquals(2, q.activeCount()) // downloading(1) + queued(3)
    }

    @Test fun `display order puts newest active first then completed in their own section`() {
        val q = RommDownloadQueue()
        q.enqueue(listOf(item(1), item(2), item(3), item(4)))
        q.setStatus("ROM-1", DownloadStatus.Done)
        q.setStatus("ROM-3", DownloadStatus.Done)
        // Insertion order is 1,2,3,4. Active (2,4) newest-first, then done (1,3) newest-first.
        assertEquals(listOf(4, 2, 3, 1), q.state.value.inDisplayOrder().map { it.rommId })
    }
}
