package dev.cannoli.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OverlayCatalogTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun platformDir(): File = File(tempFolder.root, "gba").apply { mkdirs() }

    private fun folder(parent: File, name: String): File = File(parent, name).apply { mkdirs() }

    private fun png(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }

    @Test fun `folders a loose image left by v1`() {
        val dir = platformDir()
        png(dir, "bezel.png")
        val logged = mutableListOf<String>()

        val entries = OverlayCatalog.list(dir) { logged.add(it) }

        assertEquals("bezel", entries.single())
        assertTrue(File(dir, "bezel/bezel.png").isFile)
        assertFalse(File(dir, "bezel.png").exists())
        assertTrue(logged.single().contains("foldered bezel.png"))
    }

    @Test fun `a loose image whose folder already exists is left alone`() {
        val dir = platformDir()
        png(folder(dir, "bezel"), "existing.png")
        png(dir, "bezel.png")
        val logged = mutableListOf<String>()

        OverlayCatalog.list(dir) { logged.add(it) }

        assertTrue(File(dir, "bezel.png").isFile)
        assertTrue(File(dir, "bezel/existing.png").isFile)
        assertTrue(logged.single().contains("already exists"))
    }

    @Test fun `a folder with no artwork is skipped`() {
        val dir = platformDir()
        folder(dir, "Empty")
        File(folder(dir, "Notes"), "readme.txt").writeText("hi")

        assertTrue(OverlayCatalog.list(dir).isEmpty())
    }

    @Test fun `a missing platform directory lists nothing`() {
        assertTrue(OverlayCatalog.list(File(tempFolder.root, "nope")).isEmpty())
    }

    @Test fun `entries are sorted by folder name regardless of disk order`() {
        val dir = platformDir()
        png(folder(dir, "zeta"), "z.png")
        png(folder(dir, "Alpha"), "a.png")

        assertEquals(listOf("Alpha", "zeta"), OverlayCatalog.list(dir))
    }
}
