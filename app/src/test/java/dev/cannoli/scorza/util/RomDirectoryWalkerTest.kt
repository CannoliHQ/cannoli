package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class RomDirectoryWalkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var romsDir: File
    private lateinit var walker: RomDirectoryWalker

    @Before
    fun setUp() {
        root = tempFolder.root
        romsDir = File(root, "Roms").apply { mkdirs() }

        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir

        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs

        walker = RomDirectoryWalker(paths, assets, arcade)
    }

    private fun write(relativePath: String, content: String = "") {
        val f = File(romsDir, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun m3uName(base: String) = "PS${File.separator}$base${File.separator}$base.m3u"

    @Test
    fun `bundles three loose discs into one game`() {
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")
        write("PS/Cool Game (Disc 3).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 3).chd").exists())
    }

    @Test
    fun `merges a late loose disc into an already-organized bundle`() {
        // State after a mid-upload scan already bundled discs 1 and 2.
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")
        // Disc 3 arrives loose afterwards.
        write("PS/Cool Game (Disc 3).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 3).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 3).chd").exists())

        val discs = File(romsDir, "PS/Cool Game/Cool Game.m3u").readLines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "Cool Game (Disc 1).chd",
                "Cool Game (Disc 2).chd",
                "Cool Game (Disc 3).chd",
            ),
            discs,
        )
    }

    @Test
    fun `relocates a loose m3u set into its own folder`() {
        write("PS/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game.m3u").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 2).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game.m3u").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 1).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 2).chd").exists())
    }

    @Test
    fun `writes an in-place m3u for discs in a game-named folder`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        assertTrue(m3u.exists())
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd"),
            m3u.readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `gameFiles and gameDirectory resolve an m3u folder`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game (Disc 2).chd")
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\nCool Game (Disc 2).chd\n")

        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        assertEquals(File(romsDir, "PS/Cool Game"), walker.gameDirectory(m3u))
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd", "Cool Game.m3u"),
            walker.gameFiles(m3u).map { it.name }.sorted(),
        )
        assertEquals(null, walker.gameDirectory(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd")))
    }

    @Test
    fun `gameDirectory resolves a cue folder game`() {
        write("PS/Cool Game/Cool Game.cue", "cue")
        write("PS/Cool Game/Cool Game.bin", "bin")

        val cue = File(romsDir, "PS/Cool Game/Cool Game.cue")
        assertEquals(File(romsDir, "PS/Cool Game"), walker.gameDirectory(cue))
    }

    @Test
    fun `gameDirectory is null for a loose m3u directly under the platform dir`() {
        write("PS/Loose.m3u", "Loose (Disc 1).chd\n")
        val m3u = File(romsDir, "PS/Loose.m3u")
        assertEquals(null, walker.gameDirectory(m3u))
    }

    @Test
    fun `gameFiles for a loose single file returns just that file`() {
        write("NES/Mega Game.nes")
        val rom = File(romsDir, "NES/Mega Game.nes")
        assertEquals(null, walker.gameDirectory(rom))
        assertEquals(listOf(rom), walker.gameFiles(rom))
    }

    @Test
    fun `gameDirectory is null for a loose file in a category folder`() {
        write("PS/RPGs/Some Game.iso")
        val rom = File(romsDir, "PS/RPGs/Some Game.iso")
        assertEquals(null, walker.gameDirectory(rom))
        assertEquals(listOf(rom), walker.gameFiles(rom))
    }

    @Test
    fun `categoryFolders lists non-game directories only`() {
        write("PS/RPGs/Some Game.iso")
        write("PS/Racing/Game (Disc 1).chd")
        write("PS/Racing/Game (Disc 2).chd")
        write("PS/Racing/Game.m3u", "Game (Disc 1).chd\nGame (Disc 2).chd\n")
        walker.walk("PS", isArcade = false)
        assertEquals(listOf("Racing", "RPGs"), walker.categoryFolders("PS", isArcade = false))
    }

    @Test
    fun `renaming a single-file game renames just the file`() {
        write("NES/Mega Game.nes", "rom")
        val rom = File(romsDir, "NES/Mega Game.nes")

        val result = walker.renameGame(rom, "Super Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(rom.exists())
        val renamed = File(romsDir, "NES/Super Game.nes")
        assertTrue(renamed.exists())
        assertEquals("rom", renamed.readText())
        assertEquals(renamed, result.newPrimary)
    }

    @Test
    fun `renaming a multi-disc folder game cascades to all files`() {
        write("PS/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")
        write("PS/Cool Game (Disc 3).chd")
        walker.walk("PS", isArcade = false)

        val m3u = File(romsDir, "PS/Cool Game/Cool Game.m3u")
        val result = walker.renameGame(m3u, "Rad Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(File(romsDir, "PS/Cool Game").exists())
        val newDir = File(romsDir, "PS/Rad Game")
        assertTrue(newDir.exists())
        assertTrue(File(newDir, "Rad Game.m3u").exists())
        assertTrue(File(newDir, "Rad Game (Disc 1).chd").exists())
        assertTrue(File(newDir, "Rad Game (Disc 2).chd").exists())
        assertTrue(File(newDir, "Rad Game (Disc 3).chd").exists())
        assertEquals(
            listOf(
                "Rad Game (Disc 1).chd",
                "Rad Game (Disc 2).chd",
                "Rad Game (Disc 3).chd",
            ),
            File(newDir, "Rad Game.m3u").readLines().filter { it.isNotBlank() },
        )
        assertEquals(File(newDir, "Rad Game.m3u"), result.newPrimary)
    }

    @Test
    fun `renaming a cue-based folder game cascades to all files`() {
        write("PS/Cool Game/Cool Game.cue", "cue")
        write("PS/Cool Game/Cool Game.bin", "bin")

        val cue = File(romsDir, "PS/Cool Game/Cool Game.cue")
        val result = walker.renameGame(cue, "Rad Game")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, result.outcome)
        assertFalse(File(romsDir, "PS/Cool Game").exists())
        val newDir = File(romsDir, "PS/Rad Game")
        assertTrue(newDir.exists())
        assertTrue(File(newDir, "Rad Game.cue").exists())
        assertTrue(File(newDir, "Rad Game.bin").exists())
        assertEquals(File(newDir, "Rad Game.cue"), result.newPrimary)
    }

    @Test
    fun `renaming a single-file game to its current name returns RENAMED`() {
        write("NES/Mega Game.nes", "rom")
        val rom = File(romsDir, "NES/Mega Game.nes")

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, walker.renameGame(rom, "Mega Game").outcome)
        assertTrue(rom.exists())

        assertEquals(RomDirectoryWalker.RenameOutcome.RENAMED, walker.renameGame(rom, "mega game").outcome)
        assertTrue(rom.exists())
    }

    @Test
    fun `renaming to an existing name returns NAME_TAKEN`() {
        write("NES/Mega Game.nes")
        write("NES/Super Game.nes")
        val rom = File(romsDir, "NES/Mega Game.nes")

        assertEquals(
            RomDirectoryWalker.RenameOutcome.NAME_TAKEN,
            walker.renameGame(rom, "Super Game").outcome,
        )
        assertTrue(rom.exists())
    }

    @Test
    fun `merges a loose disc into a folder that holds discs but no m3u`() {
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game (Disc 2).chd")

        val result = walker.walk("PS", isArcade = false)!!

        assertEquals(1, result.roms.size)
        assertEquals(m3uName("Cool Game"), result.roms.single().relativePath)
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 1).chd").exists())
        assertTrue(File(romsDir, "PS/Cool Game/Cool Game (Disc 2).chd").exists())
        assertFalse(File(romsDir, "PS/Cool Game (Disc 2).chd").exists())
        assertEquals(
            listOf("Cool Game (Disc 1).chd", "Cool Game (Disc 2).chd"),
            File(romsDir, "PS/Cool Game/Cool Game.m3u").readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `folder with rom named after it is one game launching that file`() {
        write("NSW/Cool Game/Cool Game.nsp", "base")
        write("NSW/Cool Game/update/Cool Game upd.nsp", "upd")
        write("NSW/Cool Game/dlc/Cool Game dlc.nsp", "dlc")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(1, result.roms.size)
        assertEquals("NSW${File.separator}Cool Game${File.separator}Cool Game.nsp", result.roms.single().relativePath)
        assertEquals("Cool Game", result.roms.single().displayName)
    }

    @Test
    fun `largest stem-matching file wins over sidecars`() {
        write("NSW/Cool Game/Cool Game.nsp", "big rom content")
        write("NSW/Cool Game/Cool Game.txt", "x")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(1, result.roms.size)
        assertEquals("NSW${File.separator}Cool Game${File.separator}Cool Game.nsp", result.roms.single().relativePath)
    }

    @Test
    fun `dirname m3u still wins over a stem-matching rom`() {
        write("PS/Cool Game/Cool Game.m3u", "Cool Game (Disc 1).chd\n")
        write("PS/Cool Game/Cool Game (Disc 1).chd")
        write("PS/Cool Game/Cool Game.chd")
        val result = walker.walk("PS", isArcade = false)!!
        assertEquals("PS${File.separator}Cool Game${File.separator}Cool Game.m3u", result.roms.single().relativePath)
    }

    @Test
    fun `folder without a stem-matching rom keeps per-file entries`() {
        write("NSW/Two Games/Game A.nsp")
        write("NSW/Two Games/Game B.nsp")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(2, result.roms.size)
    }

    @Test
    fun `matching-rom folder is not a category folder and resolves gameDirectory`() {
        write("NSW/Cool Game/Cool Game.nsp")
        write("NSW/Cool Game/update/u.nsp")
        assertEquals(emptyList<String>(), walker.categoryFolders("NSW", isArcade = false).filter { it == "Cool Game" })
        val primary = File(romsDir, "NSW/Cool Game/Cool Game.nsp")
        assertEquals(File(romsDir, "NSW/Cool Game"), walker.gameDirectory(primary))
    }

    @Test fun `folder with a stem-matching rom and other roms keeps per-file entries`() {
        write("NES/Mario/Mario.nes")
        write("NES/Mario/Mario 2.nes")
        val result = walker.walk("NES", isArcade = false)!!
        assertEquals(2, result.roms.size)
    }

    @Test
    fun `arcade chd folder beside its romset is not a game`() {
        write("MAME/area51.zip")
        write("MAME/area51/area51.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}area51.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder holding several discs is not scanned`() {
        write("MAME/blitz99.zip")
        write("MAME/blitz99/blitz99.chd")
        write("MAME/blitz99/blitz99b.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}blitz99.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder named by disc id is not a game`() {
        write("FBN/ikaruga.zip")
        write("FBN/ikaruga/gdl-0010.chd")
        val result = walker.walk("FBN", isArcade = true)!!
        assertEquals(listOf("FBN${File.separator}ikaruga.zip"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade chd folder beside a 7z romset is not a game`() {
        write("MAME/kinst.7z")
        write("MAME/kinst/kinst.chd")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(listOf("MAME${File.separator}kinst.7z"), result.roms.map { it.relativePath })
    }

    @Test
    fun `arcade support folder is not a category folder`() {
        write("MAME/blitz99.zip")
        write("MAME/blitz99/blitz99.chd")
        write("MAME/blitz99/blitz99b.chd")
        write("MAME/Fighting/sf2.zip")
        assertEquals(listOf("Fighting"), walker.categoryFolders("MAME", isArcade = true))
    }

    @Test
    fun `arcade folder without a matching romset still scans`() {
        write("MAME/Fighting/sf2.zip")
        val result = walker.walk("MAME", isArcade = true)!!
        assertEquals(
            listOf("MAME${File.separator}Fighting${File.separator}sf2.zip"),
            result.roms.map { it.relativePath },
        )
    }

    @Test
    fun `arcade support dir resolves for a romset zip`() {
        write("MAME/area51.zip")
        write("MAME/area51/area51.chd")
        val rom = File(romsDir, "MAME/area51.zip")
        assertEquals(File(romsDir, "MAME/area51"), walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir resolves for a 7z romset`() {
        write("MAME/kinst.7z")
        write("MAME/kinst/kinst.chd")
        val rom = File(romsDir, "MAME/kinst.7z")
        assertEquals(File(romsDir, "MAME/kinst"), walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir is null when the platform is not arcade`() {
        write("NSW/Cool Game.zip")
        write("NSW/Cool Game/Cool Game.nsp")
        val rom = File(romsDir, "NSW/Cool Game.zip")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = false))
    }

    @Test
    fun `arcade support dir is null without a matching folder`() {
        write("MAME/sf2.zip")
        val rom = File(romsDir, "MAME/sf2.zip")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `arcade support dir is null for a non-archive rom`() {
        write("MAME/pacman.chd")
        write("MAME/pacman/pacman.chd")
        val rom = File(romsDir, "MAME/pacman.chd")
        assertEquals(null, walker.arcadeSupportDir(rom, isArcade = true))
    }

    @Test
    fun `non-arcade folder beside a same-named archive is still a game`() {
        write("NSW/Cool Game.zip")
        write("NSW/Cool Game/Cool Game.nsp")
        val result = walker.walk("NSW", isArcade = false)!!
        assertEquals(
            listOf("NSW${File.separator}Cool Game.zip", "NSW${File.separator}Cool Game${File.separator}Cool Game.nsp"),
            result.roms.map { it.relativePath }.sorted(),
        )
    }
}
