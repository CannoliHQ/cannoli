package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test fun `multi-part renames the single top-level rom to the folder name and links it`() {
        val romDir = tmp.newFolder("Roms")
        val staging = tmp.newFolder("staging")
        File(staging, "Game v65536 (World).nsp").writeText("base")
        File(staging, "update").mkdirs()
        File(staging, "update/Game upd.nsp").writeText("upd")
        File(staging, "dlc").mkdirs()
        File(staging, "dlc/Game dlc.nsp").writeText("dlc")
        val g = game("Cool Game", "Cool Game (World)", listOf(
            f("Game v65536 (World).nsp"), f("Game upd.nsp"), f("Game dlc.nsp")))
        val result = installer.install(g, "NSW", staging, romDir)
        val dest = File(romDir, "NSW/Cool Game (World)")
        assertEquals("NSW/Cool Game (World)/Cool Game (World).nsp", result.linkRelativePath)
        assertEquals("Cool Game (World)", result.artBaseName)
        assertEquals("base", File(dest, "Cool Game (World).nsp").readText())
        assertEquals("upd", File(dest, "update/Game upd.nsp").readText())
        assertEquals("dlc", File(dest, "dlc/Game dlc.nsp").readText())
        assertTrue(!staging.exists())
    }

    @Test fun `multi-part with several top-level files links the m3u as-is`() {
        val romDir = tmp.newFolder("Roms")
        val staging = tmp.newFolder("staging")
        File(staging, "FF7 (Disc 1).bin").writeText("d1")
        File(staging, "FF7 (Disc 2).bin").writeText("d2")
        File(staging, "FF7.m3u").writeText("FF7 (Disc 1).bin\nFF7 (Disc 2).bin\n")
        val g = game("Final Fantasy VII", "Final Fantasy VII (USA)", listOf(
            f("FF7 (Disc 1).bin"), f("FF7 (Disc 2).bin"), f("FF7.m3u")))
        val result = installer.install(g, "PSX", staging, romDir)
        val dest = File(romDir, "PSX/Final Fantasy VII (USA)")
        assertEquals("PSX/Final Fantasy VII (USA)/FF7.m3u", result.linkRelativePath)
        assertTrue(File(dest, "FF7.m3u").exists())
        assertTrue(!File(dest, "Final Fantasy VII (USA).m3u").exists())
        assertTrue(File(dest, "FF7 (Disc 1).bin").exists())
        assertTrue(File(dest, "FF7 (Disc 2).bin").exists())
    }

    @Test fun `multi-part with several top-level files and no m3u links the folder`() {
        val romDir = tmp.newFolder("Roms")
        val staging = tmp.newFolder("staging")
        File(staging, "Game (Disc 1).chd").writeText("d1")
        File(staging, "Game (Disc 2).chd").writeText("d2")
        val g = game("Some Game", "Some Game", listOf(f("Game (Disc 1).chd"), f("Game (Disc 2).chd")))
        val result = installer.install(g, "PSX", staging, romDir)
        assertEquals("PSX/Some Game", result.linkRelativePath)
        assertTrue(File(romDir, "PSX/Some Game/Game (Disc 1).chd").exists())
    }

    @Test fun `multi-part rejects a game name that traverses out of the tag dir`() {
        val romDir = tmp.newFolder("Roms")
        val staging = tmp.newFolder("staging")
        File(staging, "Game (Disc 1).chd").writeText("d1")
        File(staging, "Game (Disc 2).chd").writeText("d2")
        val g = game("..", "..", listOf(f("Game (Disc 1).chd"), f("Game (Disc 2).chd")))
        assertThrows(Exception::class.java) { installer.install(g, "PSX", staging, romDir) }
        assertTrue(romDir.exists())
        assertTrue(staging.exists())
    }

    @Test fun `isMultiPart is true when the game has more than one file`() {
        assertTrue(installer.isMultiPart(game("g", "g.zip", listOf(f("a"), f("b")))))
        assertTrue(!installer.isMultiPart(game("g", "g.sfc", listOf(f("a")))))
    }
}
