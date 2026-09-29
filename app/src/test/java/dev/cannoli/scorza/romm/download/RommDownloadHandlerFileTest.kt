package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.download.DownloadStaging
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDownloadHandlerFileTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var client: RommClient
    private lateinit var links: RommLinkRepository
    private lateinit var handler: RommDownloadHandler
    private lateinit var staging: DownloadStaging

    private val base = RommFile("Tecmo Super Bowl (USA).nes", 4L, null, null, null, id = 1832, category = "game", isTopLevel = true)
    private val hack = RommFile("Tecmo Super Bowl 2025.nes", 4L, null, null, null, id = 1833, subDir = "hacks", category = "hack")
    private val tecmo = RommGame(1343, 1, "Tecmo Super Bowl", "Tecmo Super Bowl", 8L, null, null, emptyList(), emptyList(), null, listOf(base, hack))

    @Before fun setUp() {
        root = tmp.newFolder("SD")
        client = mockk(relaxed = true)
        every { client.downloadRomFile(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<File>(3).apply { parentFile?.mkdirs() }.writeText("DATA")
        }
        links = mockk(relaxed = true)
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms")
        staging = DownloadStaging { DownloadStaging.cardFolder(root) }
        handler = RommDownloadHandler(
            DownloadKind.ROM, client, RommInstaller(staging), links,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), paths, staging,
        )
    }

    @Test fun `a hack downloads its one file by id and writes no link`() {
        handler.run(rommPickedItem(tecmo, "NES", hack), { _, _ -> }, { false })

        verify { client.downloadRomFile(1343, 1833, "Tecmo Super Bowl 2025.nes", any(), any(), any(), any()) }
        verify(exactly = 0) { client.downloadRom(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { links.upsertLink(any(), any(), any()) }
        assertEquals("DATA", File(root, "Roms/NES/Tecmo Super Bowl 2025.nes").readText())
    }

    @Test fun `the base downloads its one file and links it`() {
        handler.run(rommPickedItem(tecmo, "NES"), { _, _ -> }, { false })

        verify { client.downloadRomFile(1343, 1832, "Tecmo Super Bowl (USA).nes", any(), any(), any(), any()) }
        verify { links.upsertLink(1343, "NES/Tecmo Super Bowl (USA).nes", "download") }
        assertTrue(File(root, "Roms/NES/Tecmo Super Bowl (USA).nes").isFile)
        assertTrue(DownloadStaging.cardFolder(root).list().orEmpty().isEmpty())
    }

    @Test fun `a failed download leaves nothing staged or installed`() {
        every { client.downloadRomFile(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<File>(3).writeText("DA")
            throw Exception("network gone")
        }

        runCatching { handler.run(rommPickedItem(tecmo, "NES"), { _, _ -> }, { false }) }

        assertTrue(DownloadStaging.cardFolder(root).list().orEmpty().isEmpty())
        assertTrue(File(root, "Roms/NES").list().orEmpty().isEmpty())
    }

    @Test fun `firmware is staged off the bios folder and lands whole`() {
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        val fw = dev.cannoli.scorza.romm.RommFirmware(7, "scph1001.bin", 4L, null, null, null)
        every { client.downloadFirmware(7, "scph1001.bin", any(), any(), any(), any()) } answers {
            val dest = arg<File>(2)
            assertEquals(DownloadStaging.cardFolder(root), dest.parentFile)
            dest.writeText("BIOS")
        }
        val fwHandler = RommDownloadHandler(
            DownloadKind.FIRMWARE, client, RommInstaller(staging), links,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), paths, staging,
        )
        val item = dev.cannoli.scorza.download.DownloadItem(
            "FIRMWARE-7", "scph1001.bin", DownloadKind.FIRMWARE, tag = "PS",
            payload = RommPayload(rommId = 1, firmware = fw),
        )

        fwHandler.run(item, { _, _ -> }, { false })

        assertEquals(listOf("scph1001.bin"), File(root, "BIOS/PS").list()!!.toList())
        assertEquals("BIOS", File(root, "BIOS/PS/scph1001.bin").readText())
        assertTrue(DownloadStaging.cardFolder(root).list().orEmpty().isEmpty())
    }
}
