package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicRenameTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var renamer: AtomicRename

    @Before fun setUp() {
        root = tempFolder.root
        renamer = AtomicRename(root)
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

    @Test fun `rename creates a timestamped backup that contains the rom`() {
        val rom = File(romsDir("PS"), "Backed.bin").writeWith("rom-content")

        val result = renamer.rename(rom, "Forward", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles()?.toList().orEmpty()
        assertEquals("expected exactly one backup directory", 1, tagBackups.size)
        val backup = tagBackups.first()
        assertTrue(backup.name.startsWith("Backed-"))
        assertEquals("rom-content", File(backup, "Backed.bin").readText())
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

    @Test fun `colliding target rom is preserved in the target subdirectory of the backup`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("source-content")
        File(romsDir("PS"), "Target.bin").writeWith("target-content")

        val result = renamer.rename(rom, "Target", "PS")
        assertTrue(result.success)

        // Rename still succeeds — the source moves to the target name.
        val movedRom = File(romsDir("PS"), "Target.bin")
        assertEquals("source-content", movedRom.readText())

        // The clobbered target rom is captured under <backup>/target/ for recovery.
        val tagBackups = File(backupDir(), "PS").listFiles().orEmpty()
        assertEquals(1, tagBackups.size)
        val targetBackup = File(tagBackups.first(), "target")
        assertTrue("expected target backup directory", targetBackup.isDirectory)
        assertEquals("target-content", File(targetBackup, "Target.bin").readText())
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

    @Test fun `target subdirectory is omitted when nothing collides at the new name`() {
        val rom = File(romsDir("PS"), "Source.bin").writeWith("rom")

        val result = renamer.rename(rom, "Pristine", "PS")
        assertTrue(result.success)

        val tagBackups = File(backupDir(), "PS").listFiles().orEmpty()
        val targetBackup = File(tagBackups.first(), "target")
        assertFalse("no collisions should mean no target backup dir", targetBackup.exists())
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
}
