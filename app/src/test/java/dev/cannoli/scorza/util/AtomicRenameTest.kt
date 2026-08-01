package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class AtomicRenameTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var renamer: AtomicRename
    private lateinit var artwork: ArtworkLookup

    private fun buildPaths(root: File): CannoliPathsProvider {
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns File(root, "Roms")
        return paths
    }

    private fun buildWalker(root: File): RomDirectoryWalker {
        val romsDir = File(root, "Roms").also { it.mkdirs() }
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()
        val paths = buildPaths(root)
        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
        return RomDirectoryWalker(paths, assets, arcade)
    }

    @Before fun setUp() {
        root = tempFolder.root
        val paths = buildPaths(root)
        artwork = ArtworkLookup(paths)
        renamer = AtomicRename(root, buildWalker(root), artwork)
    }

    private fun romsDir(tag: String) = File(root, "Roms/$tag").also { it.mkdirs() }
    private fun artDir(tag: String) = File(root, "Art/$tag").also { it.mkdirs() }
    private fun savesDir(tag: String) = File(root, "Saves/$tag").also { it.mkdirs() }
    private fun statesDir(tag: String) = File(root, "Save States/$tag").also { it.mkdirs() }
    private fun backupDir() = File(root, "Backup")

    private fun File.writeWith(content: String): File {
        writeText(content)
        return this
    }

    @Test fun `rename moves rom file to new name`() {
        val rom = File(romsDir("PS"), "OldName.bin").writeWith("rom-bytes")

        val result = renamer.rename(rom, "NewName", "PS")

        assertTrue(result.success)
        assertNull(result.error)
        assertFalse("old rom still present", rom.exists())
        val newRom = File(romsDir("PS"), "NewName.bin")
        assertTrue("new rom missing", newRom.exists())
        assertEquals("rom-bytes", newRom.readText())
    }

    @Test fun `rename moves matching art save and state files`() {
        val rom = File(romsDir("PS"), "Game.bin").writeWith("rom")
        File(artDir("PS"), "Game.png").writeWith("art")
        File(savesDir("PS"), "Game.srm").writeWith("save")
        File(statesDir("PS"), "Game.state").writeWith("state")

        val result = renamer.rename(rom, "Renamed", "PS")
        assertTrue(result.success)

        assertTrue(File(artDir("PS"), "Renamed.png").exists())
        assertFalse(File(artDir("PS"), "Game.png").exists())
        assertTrue(File(savesDir("PS"), "Renamed.srm").exists())
        assertFalse(File(savesDir("PS"), "Game.srm").exists())
        assertTrue(File(statesDir("PS"), "Renamed.state").exists())
        assertFalse(File(statesDir("PS"), "Game.state").exists())
    }

    @Test fun `rename moves the per-game state subdirectory`() {
        val rom = File(romsDir("PS"), "Disc.bin").writeWith("rom")
        val stateSub = File(statesDir("PS"), "Disc").also { it.mkdirs() }
        File(stateSub, "slot0.state").writeWith("inner")

        val result = renamer.rename(rom, "DiscNew", "PS")
        assertTrue(result.success)

        val movedSub = File(statesDir("PS"), "DiscNew")
        assertTrue(movedSub.isDirectory)
        assertEquals("inner", File(movedSub, "slot0.state").readText())
        assertFalse(stateSub.exists())
    }

    @Test fun `rename backs up save data but never copies the rom`() {
        val rom = File(romsDir("PS"), "Backed.bin").writeWith("rom-content")
        File(savesDir("PS"), "Backed.srm").writeWith("save-content")

        val result = renamer.rename(rom, "Forward", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles()?.toList().orEmpty()
        assertEquals("expected exactly one backup directory", 1, tagBackups.size)
        val backup = tagBackups.first()
        assertTrue(backup.name.startsWith("Backed-"))
        assertEquals("save-content", File(backup, "saves_Backed.srm").readText())
        assertFalse("rom must not be copied into the backup", File(backup, "Backed.bin").exists())
    }

    @Test fun `rename leaves no backup directory when there is no save data`() {
        val rom = File(romsDir("PS"), "Backed.bin").writeWith("rom-content")

        val result = renamer.rename(rom, "Forward", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles()?.toList().orEmpty()
        assertTrue("no backup dir expected when nothing needs preserving", tagBackups.isEmpty())
    }

    @Test fun `art files of multiple supported extensions are detected`() {
        val rom = File(romsDir("GB"), "Cart.gb").writeWith("rom")
        File(artDir("GB"), "Cart.jpg").writeWith("jpg-art")

        val result = renamer.rename(rom, "Renamed", "GB")
        assertTrue(result.success)

        assertTrue(File(artDir("GB"), "Renamed.jpg").exists())
        assertFalse(File(artDir("GB"), "Cart.jpg").exists())
    }

    @Test fun `rename succeeds even with no save or state files present`() {
        val rom = File(romsDir("NES"), "Lone.nes").writeWith("rom")
        // no art, saves, or states

        val result = renamer.rename(rom, "Solo", "NES")
        assertTrue(result.success)

        assertTrue(File(romsDir("NES"), "Solo.nes").exists())
        assertFalse(rom.exists())
    }

    @Test fun `rename onto an existing rom name fails without touching either file`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("source-content")
        File(romsDir("PS"), "Target.bin").writeWith("target-content")

        val result = renamer.rename(rom, "Target", "PS")
        assertFalse("must refuse to clobber an existing rom", result.success)

        // Neither file is moved or overwritten.
        assertEquals("source-content", File(romsDir("PS"), "Source.bin").readText())
        assertEquals("target-content", File(romsDir("PS"), "Target.bin").readText())
    }

    @Test fun `colliding target saves and states are preserved in the target backup`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("rom")
        File(savesDir("PS"), "Source.srm").writeWith("source-save")
        File(statesDir("PS"), "Source.state").writeWith("source-state")
        // Files already at the target name that would be silently clobbered:
        File(savesDir("PS"), "Target.srm").writeWith("target-save")
        File(statesDir("PS"), "Target.state").writeWith("target-state")

        val result = renamer.rename(rom, "Target", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles().orEmpty()
        val targetBackup = File(tagBackups.first(), "target")
        assertTrue(targetBackup.isDirectory)
        assertEquals("target-save", File(targetBackup, "saves_Target.srm").readText())
        assertEquals("target-state", File(targetBackup, "states_Target.state").readText())
    }

    @Test fun `colliding target state subdirectory is preserved`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("rom")
        val targetSub = File(statesDir("PS"), "Target").also { it.mkdirs() }
        File(targetSub, "slot1.state").writeWith("inside-target")

        val result = renamer.rename(rom, "Target", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles().orEmpty()
        val targetBackup = File(tagBackups.first(), "target")
        val savedSub = File(targetBackup, "statedir_Target")
        assertTrue(savedSub.isDirectory)
        assertEquals("inside-target", File(savedSub, "slot1.state").readText())
    }

    @Test fun `no backup directory is created when nothing collides at the new name`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("rom")

        val result = renamer.rename(rom, "Pristine", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles()?.toList().orEmpty()
        assertTrue("no save data and no collisions means no backup dir", tagBackups.isEmpty())
    }

    @Test fun `map_txt is updated in place when present`() {
        val romsTag = romsDir("PS")
        val rom = File(romsTag, "Old.bin").writeWith("rom")
        File(romsTag, "map.txt").writeWith(
            "Old.bin\tDisplay Name\n" +
                "Other.bin\tAnother Game\n"
        )

        val result = renamer.rename(rom, "New", "PS")
        assertTrue(result.success)

        val mapText = File(romsTag, "map.txt").readText().trim()
        assertEquals(
            "New.bin\tDisplay Name\n" +
                "Other.bin\tAnother Game",
            mapText
        )
    }

    @Test fun `rename cascades a folder game and reports the new primary`() {
        val tagDir = romsDir("NSW")
        val gameDir = File(tagDir, "Old Game").also { it.mkdirs() }
        val rom = File(gameDir, "Old Game.nsp").writeWith("rom")
        File(gameDir, "updates").mkdirs()
        File(gameDir, "updates/patch.nsp").writeWith("upd")
        val art = File(artDir("NSW"), "Old Game.png").writeWith("art")
        val save = File(savesDir("NSW"), "Old Game.srm").writeWith("save")

        val result = renamer.rename(rom, "New Game", "NSW")

        assertTrue(result.success)
        val newDir = File(tagDir, "New Game")
        assertEquals(File(newDir, "New Game.nsp"), result.newPrimary)
        assertEquals("rom", File(newDir, "New Game.nsp").readText())
        assertEquals("upd", File(newDir, "updates/patch.nsp").readText())
        assertFalse(gameDir.exists())
        assertTrue(File(artDir("NSW"), "New Game.png").exists())
        assertTrue(File(savesDir("NSW"), "New Game.srm").exists())
        assertFalse(art.exists())
        assertFalse(save.exists())
    }

    @Test fun `folder game rename fails with ALREADY_EXISTS when target folder taken`() {
        val tagDir = romsDir("NSW")
        val gameDir = File(tagDir, "Old Game").also { it.mkdirs() }
        val rom = File(gameDir, "Old Game.nsp").writeWith("rom")
        File(tagDir, "New Game").mkdirs()

        val result = renamer.rename(rom, "New Game", "NSW")

        assertFalse(result.success)
        assertEquals(AtomicRename.RenameError.ALREADY_EXISTS, result.error)
        assertTrue(rom.exists())
    }

    @Test fun `renamed art is resolvable through a warm ArtworkLookup cache`() {
        val rom = File(romsDir("PS"), "Old.bin").writeWith("rom")
        File(artDir("PS"), "Old.png").writeWith("art")
        assertTrue(artwork.findByName("PS", "Old") != null)

        val result = renamer.rename(rom, "New", "PS")

        assertTrue(result.success)
        assertTrue(artwork.findByName("PS", "New") != null)
        assertTrue(artwork.findByName("PS", "Old") == null)
    }
}
