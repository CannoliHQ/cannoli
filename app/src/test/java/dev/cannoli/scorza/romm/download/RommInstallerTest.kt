package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RommInstallerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val installer = RommInstaller()

    private fun game(name: String, fsName: String, files: List<RommFile>) =
        RommGame(1, 1, name, fsName, 0, null, null, emptyList(), emptyList(), null, files)
    private fun f(n: String) = RommFile(n, 0, null, null, null)

    @Test fun `single-file installs by atomic rename and links the file path`() {
        val romDir = tmp.newFolder("Roms")
        val temp = File(tmp.newFolder(), "tmp.bin").apply { writeText("rom") }
        val g = game("Chrono Trigger", "Chrono Trigger (USA).sfc", listOf(f("Chrono Trigger (USA).sfc")))
        val result = installer.install(g, "SNES", temp, romDir)
        assertEquals("SNES/Chrono Trigger (USA).sfc", result.linkRelativePath)
        assertEquals("Chrono Trigger (USA)", result.artBaseName)
        assertTrue(File(romDir, "SNES/Chrono Trigger (USA).sfc").readText() == "rom")
        assertTrue(!temp.exists())
    }

    @Test fun `multi-part extracts the zip into a per-game subfolder and links the m3u`() {
        val romDir = tmp.newFolder("Roms")
        val zip = File(tmp.newFolder(), "z.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in listOf("FF7 (Disc 1).bin", "FF7 (Disc 2).bin", "FF7.m3u")) {
                z.putNextEntry(ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry()
            }
        }
        val g = game("Final Fantasy VII", "Final Fantasy VII.zip",
            listOf(f("FF7 (Disc 1).bin"), f("FF7 (Disc 2).bin")))
        val result = installer.install(g, "PSX", zip, romDir)
        val sub = File(romDir, "PSX/Final Fantasy VII")
        assertTrue(File(sub, "FF7 (Disc 1).bin").exists())
        assertTrue(File(sub, "FF7.m3u").exists())
        assertEquals("PSX/Final Fantasy VII/FF7.m3u", result.linkRelativePath)
        assertEquals("Final Fantasy VII", result.artBaseName)
        assertTrue(!zip.exists())
    }

    @Test fun `isMultiPart is true when the game has more than one file`() {
        assertTrue(installer.isMultiPart(game("g", "g.zip", listOf(f("a"), f("b")))))
        assertTrue(!installer.isMultiPart(game("g", "g.sfc", listOf(f("a")))))
    }
}
