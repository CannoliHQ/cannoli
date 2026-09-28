package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.download.DownloadKind
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
        handler = RommDownloadHandler(
            DownloadKind.ROM, client, RommInstaller(), links,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), paths,
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
    }
}
