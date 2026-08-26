package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Identity, name and size used to be computed properties on a RomM-shaped queue item. The queue is
 * generic now and holds them as plain fields, so the RomM knowledge lives here instead: these are
 * the same rules, in the one place that still understands what a RomM transfer is.
 */
class RommDownloadItemsTest {

    private fun game(id: Int) =
        RommGame(id, 1, "Game $id", "g$id.sfc", 42L, null, null, emptyList(), emptyList(), null, emptyList())

    @Test fun `a rom item is keyed by kind and id`() {
        val item = rommItem(game(7), "SNES")
        assertEquals("ROM-7", item.key)
        assertEquals("Game 7", item.displayName)
        assertEquals(42L, item.sizeBytes)
        assertEquals("SNES", item.tag)
    }

    // The same game queued as a rom and as its manual are two transfers, so they must not dedupe
    // against each other.
    @Test fun `a manual of the same game is a different key`() {
        assertEquals("MANUAL-7", rommItem(game(7), "SNES", DownloadKind.MANUAL).key)
    }

    @Test fun `firmware is named by its file and keyed by its own id`() {
        val fw = RommFirmware(9, "scph5501.bin", 100L, null, null, null)
        val item = firmwareItem(fw, "PSX")
        assertEquals("FIRMWARE-9", item.key)
        assertEquals("scph5501.bin", item.displayName)
        assertEquals(100L, item.sizeBytes)
    }

    @Test fun `the payload carries what a handler needs and the queue ignores`() {
        val p = rommItem(game(3), "SNES").payload as RommPayload
        assertEquals(3, p.rommId)
        assertEquals("Game 3", p.game?.name)
    }
}
