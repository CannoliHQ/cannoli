package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test fun seedRomFolderOnce_creates_the_tag_folder_and_marker() {
        val rom = tmp.newFolder("Roms")
        val state = tmp.newFolder("State")
        assertTrue(DirectoryLayout.seedRomFolderOnce(rom, state, "PC"))
        assertTrue(File(rom, "PC").isDirectory)
        assertTrue(File(state, ".seeded_PC").isFile)
    }

    @Test fun seedRomFolderOnce_does_not_recreate_a_deleted_folder() {
        val rom = tmp.newFolder("Roms")
        val state = tmp.newFolder("State")
        DirectoryLayout.seedRomFolderOnce(rom, state, "PC")
        File(rom, "PC").delete()

        assertFalse(DirectoryLayout.seedRomFolderOnce(rom, state, "PC"))
        assertFalse(File(rom, "PC").exists())
    }

    @Test fun ensure_seeds_the_pc_rom_folder_on_an_existing_install() {
        val root = tmp.newFolder("cannoli")
        val rom = tmp.newFolder("Roms")
        File(rom, "NES").mkdirs()
        val assets = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().assets
        val config = dev.cannoli.scorza.config.PlatformConfig({ root }, assets)
        DirectoryLayout.ensure(root, rom, assets, config)

        assertTrue(File(rom, "PC").isDirectory)
        assertFalse(File(rom, "SNES").exists())
    }

    @Test fun ensure_creates_tools_and_ports_art_dirs() {
        val root = tmp.newFolder("cannoli")
        val rom = tmp.newFolder("Roms")
        val assets = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().assets
        val config = dev.cannoli.scorza.config.PlatformConfig({ root }, assets)
        DirectoryLayout.ensure(root, rom, assets, config)

        assertTrue(File(root, "Art/TOOLS").isDirectory)
        assertTrue(File(root, "Art/PORTS").isDirectory)
    }
}
