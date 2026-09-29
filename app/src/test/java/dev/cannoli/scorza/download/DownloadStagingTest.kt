package dev.cannoli.scorza.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DownloadStagingTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var folder: File
    private lateinit var staging: DownloadStaging
    private val logged = mutableListOf<String>()

    @Before fun setUp() {
        root = tmp.newFolder("SD")
        folder = DownloadStaging.cardFolder(root)
        staging = DownloadStaging { folder }
    }

    private fun file(parent: File, name: String, bytes: Int = 4) =
        File(parent, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test fun `every stage is unique and sits in the staging folder`() {
        val stages = (1..50).map { staging.file() } + (1..50).map { staging.dir() }
        assertEquals(stages.size, stages.map { it.name }.toSet().size)
        assertTrue(stages.all { it.parentFile == folder })
        assertTrue(staging.dir().isDirectory)
    }

    @Test fun `commit moves the stage onto its destination, replacing what was there`() {
        val dest = file(root, "Art/NES/Game.png").apply { writeText("old") }
        val stage = staging.file().apply { writeText("new") }

        staging.commit(stage, dest)

        assertEquals("new", dest.readText())
        assertFalse(stage.exists())
        assertEquals(listOf("Game.png"), dest.parentFile!!.list()!!.toList())
    }

    @Test fun `commit replaces a directory destination`() {
        val dest = File(root, "Roms/PSX/Game").apply { mkdirs() }
        file(dest, "old.bin")
        val stage = staging.dir()
        file(stage, "Game (Disc 1).bin")

        staging.commit(stage, dest)

        assertEquals(listOf("Game (Disc 1).bin"), dest.list()!!.toList())
        assertFalse(stage.exists())
    }

    // No second volume in a unit test, so the copy fallback cannot succeed here. A destination
    // whose parent is a file drives both renames and the copy to fail instead.
    @Test fun `a commit that cannot land leaves nothing staged`() {
        val blocker = file(root, "Roms")
        val stage = staging.file().apply { writeText("rom") }

        val error = runCatching { staging.commit(stage, File(blocker, "NES/Game.nes")) }.exceptionOrNull()

        assertTrue(error != null)
        assertFalse(stage.exists())
    }

    @Test fun `a commit that refuses a copy fails before copying`() {
        val blocker = file(root, "Roms")
        val stage = staging.file().apply { writeText("core") }

        val error = runCatching { staging.commit(stage, File(blocker, "cores/a.so"), allowCopy = false) }.exceptionOrNull()

        assertTrue(error is java.io.IOException && error.message.orEmpty().startsWith("could not move"))
        assertFalse(stage.exists())
    }

    @Test fun `discard removes a file or a directory stage`() {
        val f = staging.file().apply { writeText("x") }
        val d = staging.dir()
        file(d, "sub/a.bin")

        staging.discard(f)
        staging.discard(d)

        assertFalse(f.exists())
        assertFalse(d.exists())
    }

    @Test fun `the start-up sweep empties Downloads and removes the old RommDownloads folder`() {
        file(folder, "abc.part", 10)
        file(File(folder, "def.parts"), "disc1.bin", 3)
        file(File(folder, "def.parts"), "sub/disc2.bin", 5)
        val legacy = File(root, "Config/Cache/RommDownloads")
        file(legacy, "454.part", 9)
        file(File(legacy, "99.parts"), "disc1.bin")

        DownloadStaging.sweep(root) { logged += it }

        assertTrue(folder.list()!!.isEmpty())
        assertFalse(legacy.exists())
        assertEquals(4, logged.size)
        assertTrue(logged.any { it.contains("abc.part") && it.contains("10 bytes") })
        assertTrue(logged.any { it.contains("def.parts") && it.contains("8 bytes") })
        assertTrue(logged.any { it.contains("454.part") && it.contains("9 bytes") })
    }

    @Test fun `the sweep touches nothing outside those two folders`() {
        val rom = file(root, "Roms/NES/Game.nes")
        val bios = file(root, "BIOS/PS/scph1001.bin.part")
        val sibling = file(File(root, "Config/Cache"), "5.part")
        val art = file(File(root, "Config/Cache/RommArt"), "x.png")

        DownloadStaging.sweep(root) { logged += it }

        listOf(rom, bios, sibling, art).forEach { assertTrue(it.path, it.exists()) }
        assertTrue(logged.isEmpty())
    }

    @Test fun `a link inside a staged directory is removed without following it`() {
        val outside = file(root, "Roms/NES/Keep.nes")
        val parts = File(folder, "3.parts").apply { mkdirs() }
        Files.createSymbolicLink(File(parts, "link").toPath(), outside.parentFile!!.toPath())

        DownloadStaging.sweep(root) { logged += it }

        assertFalse(parts.exists())
        assertTrue(outside.exists())
    }

    @Test fun `missing folders are fine`() {
        DownloadStaging.sweep(root) { logged += it }

        assertTrue(logged.isEmpty())
    }
}
