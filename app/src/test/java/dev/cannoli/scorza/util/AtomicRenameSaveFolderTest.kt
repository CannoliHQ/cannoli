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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/**
 * A save now lives in the game's own folder, so a rename has to carry the folder the way it already
 * carries the per-game state folder. Missing it strands the save under the old name, where nothing
 * looks for it.
 */
class AtomicRenameSaveFolderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, text: String) { f.parentFile?.mkdirs(); f.writeText(text) }

    private fun renamer(root: File): AtomicRename {
        val romsDir = File(root, "Roms").also { it.mkdirs() }
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir
        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
        val artwork = ArtworkLookup(paths)
        return AtomicRename(root, RomDirectoryWalker(paths, assets, arcade), artwork)
    }

    private fun rom(root: File, tag: String, base: String) =
        File(root, "Roms/$tag/$base.sfc").apply { parentFile?.mkdirs(); writeText("rom") }

    @Test fun `rename carries the save folder and the files named after the game`() {
        val root = tmp.root
        write(File(root, "Saves/SNES/Old Game/Old Game.srm"), "SAVE")
        write(File(root, "Saves/SNES/Old Game/Old Game.rtc"), "RTC")

        val result = renamer(root).rename(rom(root, "SNES", "Old Game"), "New Game", "SNES")

        assertTrue(result.success)
        assertFalse(File(root, "Saves/SNES/Old Game").exists())
        assertEquals("SAVE", File(root, "Saves/SNES/New Game/New Game.srm").readText())
        assertEquals("RTC", File(root, "Saves/SNES/New Game/New Game.rtc").readText())
    }

    /** The emulator names some of its own files; those keep their names when the game changes. */
    @Test fun `a file the emulator named is carried without being renamed`() {
        val root = tmp.root
        write(File(root, "Saves/PSP/Old Game/ULUS10064/DATA.BIN"), "SAVE")

        assertTrue(renamer(root).rename(rom(root, "PSP", "Old Game"), "New Game", "PSP").success)

        assertEquals("SAVE", File(root, "Saves/PSP/New Game/ULUS10064/DATA.BIN").readText())
    }

    @Test fun `the folder is backed up before it moves`() {
        val root = tmp.root
        write(File(root, "Saves/SNES/Old Game/Old Game.srm"), "SAVE")

        renamer(root).rename(rom(root, "SNES", "Old Game"), "New Game", "SNES")

        val backups = File(root, "Backup/SNES").listFiles().orEmpty()
        assertTrue(backups.any { File(it, "savedir_Old Game/Old Game.srm").isFile })
    }

    /** A loose save on an install the sweep has not reached still renames as it always did. */
    @Test fun `a loose save is still carried`() {
        val root = tmp.root
        write(File(root, "Saves/SNES/Old Game.srm"), "SAVE")

        assertTrue(renamer(root).rename(rom(root, "SNES", "Old Game"), "New Game", "SNES").success)

        assertEquals("SAVE", File(root, "Saves/SNES/New Game.srm").readText())
    }
}
