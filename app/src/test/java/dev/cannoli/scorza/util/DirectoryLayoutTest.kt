package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectoryLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun needsScaffold_true_for_empty_dir() {
        val rom = tmp.newFolder("Roms")
        assertTrue(DirectoryLayout.romDirNeedsScaffold(rom))
    }

    @Test fun needsScaffold_true_for_nonexistent_dir() {
        val rom = File(tmp.root, "DoesNotExist")
        assertTrue(DirectoryLayout.romDirNeedsScaffold(rom))
    }

    @Test fun needsScaffold_true_when_only_files_present() {
        val rom = tmp.newFolder("Roms")
        File(rom, "loose.nes").writeBytes(ByteArray(1))
        File(rom, ".nomedia").writeBytes(ByteArray(0))
        assertTrue(DirectoryLayout.romDirNeedsScaffold(rom))
    }

    @Test fun needsScaffold_false_when_subfolder_present() {
        val rom = tmp.newFolder("Roms")
        File(rom, "NES").mkdirs()
        assertFalse(DirectoryLayout.romDirNeedsScaffold(rom))
    }

    @Test fun needsScaffold_true_when_only_hidden_subfolder_present() {
        val rom = tmp.newFolder("Roms")
        File(rom, ".thumbnails").mkdirs()
        assertTrue(DirectoryLayout.romDirNeedsScaffold(rom))
    }

    @Test fun scaffoldRomFolders_creates_all_tags_and_counts() {
        val rom = tmp.newFolder("Roms")
        val created = DirectoryLayout.scaffoldRomFolders(rom, listOf("NES", "SNES", "PS"))
        assertEquals(3, created)
        assertTrue(File(rom, "NES").isDirectory)
        assertTrue(File(rom, "SNES").isDirectory)
        assertTrue(File(rom, "PS").isDirectory)
    }

    @Test fun scaffoldRomFolders_is_idempotent() {
        val rom = tmp.newFolder("Roms")
        DirectoryLayout.scaffoldRomFolders(rom, listOf("NES"))
        val createdAgain = DirectoryLayout.scaffoldRomFolders(rom, listOf("NES"))
        assertEquals(0, createdAgain)
    }
}
