package dev.cannoli.scorza.server

import android.content.res.AssetManager
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.RomDirectoryWalker
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameMoveHandlerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val assets = mockk<AssetManager>(relaxed = true)
    private val session = mockk<NanoHTTPD.IHTTPSession>(relaxed = true)

    private fun makeRom(id: Long, path: File, tag: String = "NES") = Rom(
        id = id,
        path = path,
        platformTag = tag,
        displayName = path.nameWithoutExtension,
        tags = null,
        artFile = null,
        launchTarget = LaunchTarget.RetroArch,
        raGameId = null,
    )

    private fun makeServer(
        root: File,
        repo: RomsRepository,
        walker: RomDirectoryWalker? = null,
    ): KitchenHttpServer = KitchenHttpServer(
        cannoliRoot = root,
        assets = assets,
        romsRootProvider = { File(root, "Roms") },
        pin = "TEST",
        romsRepository = repo,
        romDirectoryWalker = walker,
    )

    @Test
    fun `single-file game moves into subfolder`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val rpgsDir = File(nesDir, "RPGs").also { it.mkdirs() }
        val romFile = File(nesDir, "Game.nes").also { it.writeText("ROM") }

        val rom = makeRom(1L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(1L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "1", "move"),
            query = mapOf("folder" to "RPGs"),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(200, response.status.requestStatus)
        assertFalse(romFile.exists())
        assertTrue(File(rpgsDir, "Game.nes").exists())
    }

    @Test
    fun `multi-disc folder game moves as a whole folder`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val destDir = File(nesDir, "Classics").also { it.mkdirs() }
        val gameFolder = File(nesDir, "MyGame").also { it.mkdirs() }
        File(gameFolder, "MyGame.m3u").writeText("MyGame (Disc 1).bin\n")
        File(gameFolder, "MyGame (Disc 1).bin").writeText("BIN1")
        File(gameFolder, "MyGame (Disc 2).bin").writeText("BIN2")

        val m3u = File(gameFolder, "MyGame.m3u")
        val rom = makeRom(2L, m3u, "NES")

        val repo = mockk<RomsRepository>()
        every { repo.gameById(2L) } returns rom

        val walker = mockk<RomDirectoryWalker>()
        every { walker.gameDirectory(m3u) } returns gameFolder

        val server = makeServer(tmp.root, repo, walker)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "2", "move"),
            query = mapOf("folder" to "Classics"),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(200, response.status.requestStatus)
        assertFalse(gameFolder.exists())
        assertTrue(File(destDir, "MyGame").isDirectory)
        assertTrue(File(destDir, "MyGame/MyGame.m3u").exists())
    }

    @Test
    fun `moving onto existing name returns 409`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val rpgsDir = File(nesDir, "RPGs").also { it.mkdirs() }
        val romFile = File(nesDir, "Game.nes").also { it.writeText("ROM") }
        File(rpgsDir, "Game.nes").writeText("EXISTING")

        val rom = makeRom(3L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(3L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "3", "move"),
            query = mapOf("folder" to "RPGs"),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(409, response.status.requestStatus)
        assertTrue(romFile.exists())
    }

    @Test
    fun `non-POST returns 405`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val romFile = File(nesDir, "Game.nes").also { it.writeText("ROM") }

        val rom = makeRom(4L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(4L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "GET",
            segments = listOf("NES", "4", "move"),
            query = emptyMap(),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(405, response.status.requestStatus)
    }

    @Test
    fun `absent folder param moves to platform root`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val subDir = File(nesDir, "Action").also { it.mkdirs() }
        val romFile = File(subDir, "Game.nes").also { it.writeText("ROM") }

        val rom = makeRom(5L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(5L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "5", "move"),
            query = emptyMap(),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(200, response.status.requestStatus)
        assertFalse(romFile.exists())
        assertTrue(File(nesDir, "Game.nes").exists())
    }

    @Test
    fun `missing destination folder returns 400`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val romFile = File(nesDir, "Game.nes").also { it.writeText("ROM") }

        val rom = makeRom(6L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(6L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "6", "move"),
            query = mapOf("folder" to "NonExistent"),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(400, response.status.requestStatus)
        assertTrue(romFile.exists())
    }

    @Test
    fun `moving game into its current folder is a no-op and returns 200`() {
        val nesDir = File(tmp.root, "Roms/NES").also { it.mkdirs() }
        val subDir = File(nesDir, "RPGs").also { it.mkdirs() }
        val romFile = File(subDir, "Game.nes").also { it.writeText("ROM") }

        val rom = makeRom(7L, romFile)
        val repo = mockk<RomsRepository>()
        every { repo.gameById(7L) } returns rom

        val server = makeServer(tmp.root, repo)
        val response = server.handleGames(
            method = "POST",
            segments = listOf("NES", "7", "move"),
            query = mapOf("folder" to "RPGs"),
            headers = emptyMap(),
            session = session,
        )

        assertEquals(200, response.status.requestStatus)
        assertTrue(romFile.exists())
    }
}
