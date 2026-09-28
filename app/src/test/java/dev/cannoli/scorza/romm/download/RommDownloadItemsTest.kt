package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val base = RommFile("Tecmo Super Bowl (USA).nes", 40L, null, null, null, id = 1832, category = "game", isTopLevel = true)
    private val hack = RommFile("Tecmo Super Bowl 2025.nes", 41L, null, null, null, id = 1833, subDir = "hacks", category = "hack")
    private val tecmo = RommGame(1343, 1, "Tecmo Super Bowl", "Tecmo Super Bowl", 81L, null, null, emptyList(), emptyList(), null, listOf(base, hack))

    @Test fun `a picked hack carries its file id and installs as a single file`() {
        val item = rommPickedItem(tecmo, "NES", hack)
        val p = item.payload as RommPayload
        assertEquals("ROM-1343-1833", item.key)
        assertEquals("Tecmo Super Bowl 2025", item.displayName)
        assertEquals(41L, item.sizeBytes)
        assertEquals(1343, p.rommId)
        assertEquals(1833, p.file?.id)
        assertEquals("Tecmo Super Bowl 2025.nes", p.game?.fsName)
        assertEquals(listOf(hack), p.game?.files)
    }

    @Test fun `the picked base of a hacks entry fetches only its top-level game file`() {
        val item = rommPickedItem(tecmo, "NES")
        val p = item.payload as RommPayload
        assertEquals("ROM-1343-1832", item.key)
        assertEquals(1832, p.file?.id)
        assertEquals("Tecmo Super Bowl (USA).nes", p.game?.fsName)
        assertEquals(40L, item.sizeBytes)
    }

    @Test fun `an entry with no hacks downloads whole as before`() {
        val item = rommPickedItem(game(7), "SNES")
        assertEquals("ROM-7", item.key)
        assertNull((item.payload as RommPayload).file)
    }

    // Bulk download (Select, then Start) queues each checked row through rommPickedItem with no
    // hack file, the same call the picker's base row makes.
    @Test fun `a bulk pick queues a hacks entry's base file and a plain entry whole`() {
        val items = listOf(tecmo, game(7)).map { rommPickedItem(it, "NES") }
        assertEquals(listOf("ROM-1343-1832", "ROM-7"), items.map { it.key })
        assertEquals(listOf(1832, null), items.map { (it.payload as RommPayload).file?.id })
    }
}
