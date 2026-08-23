package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.CannoliPaths
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

class AtomicRenameContentTest {

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

    @Test fun `rename moves the cheats dir the guides dir and the per-game config file`() {
        val root = tmp.root
        val tag = "PS"
        val oldBase = "Old Game"
        val newBase = "New Game"
        val paths = CannoliPaths(root)
        val rom = File(root, "Roms/$tag/$oldBase.bin").apply { parentFile?.mkdirs(); writeText("rom") }
        write(File(paths.cheatDir(tag, oldBase), "cheats.cht"), "C")
        write(File(paths.guideDir(tag, oldBase), "guide.txt"), "G")
        write(paths.gameOverrideCfg(tag, oldBase, "nestopia"), "K = V")
        write(paths.gameOverrideCfg(tag, oldBase, "fceumm"), "K = V2")

        val result = renamer(root).rename(rom, newBase, tag)

        assertTrue(result.success)
        assertFalse("old cheats dir must be gone", paths.cheatDir(tag, oldBase).exists())
        assertFalse("old guides dir must be gone", paths.guideDir(tag, oldBase).exists())
        assertFalse("old override dir must be gone", paths.gameOverrideDir(tag, oldBase).exists())
        assertEquals("C", File(paths.cheatDir(tag, newBase), "cheats.cht").readText())
        assertEquals("G", File(paths.guideDir(tag, newBase), "guide.txt").readText())
        assertEquals("K = V", paths.gameOverrideCfg(tag, newBase, "nestopia").readText())
        assertEquals("K = V2", paths.gameOverrideCfg(tag, newBase, "fceumm").readText())
    }

    @Test fun `cheats dir collision at the target rolls back the whole rename without backing anything up`() {
        val root = tmp.root
        val tag = "PS"
        val oldBase = "Old Game"
        val newBase = "New Game"
        val paths = CannoliPaths(root)
        val rom = File(root, "Roms/$tag/$oldBase.bin").apply { parentFile?.mkdirs(); writeText("rom") }
        write(File(paths.cheatDir(tag, oldBase), "cheats.cht"), "old-cheat")
        write(File(paths.cheatDir(tag, newBase), "existing.cht"), "target-cheat")

        val result = renamer(root).rename(rom, newBase, tag)

        assertFalse("collision must fail the whole rename", result.success)
        assertEquals(AtomicRename.RenameError.ALREADY_EXISTS, result.error)
        // Nothing lost or moved: rom stays put, both cheats dirs remain exactly as they were.
        assertTrue("rom must roll back to its old name", rom.exists())
        assertFalse(File(root, "Roms/$tag/$newBase.bin").exists())
        assertEquals("old-cheat", File(paths.cheatDir(tag, oldBase), "cheats.cht").readText())
        assertEquals("target-cheat", File(paths.cheatDir(tag, newBase), "existing.cht").readText())
        // Neither side was ever moved, so there's nothing to back up: no delete-then-recopy
        // window for rollback to lose content in.
        assertTrue(
            "collision must be detected before anything is backed up",
            File(root, "Backup/$tag").listFiles()?.isEmpty() ?: true
        )
    }

    @Test fun `guides dir collision at the target rolls back the whole rename without backing anything up`() {
        val root = tmp.root
        val tag = "PS"
        val oldBase = "Old Game"
        val newBase = "New Game"
        val paths = CannoliPaths(root)
        val rom = File(root, "Roms/$tag/$oldBase.bin").apply { parentFile?.mkdirs(); writeText("rom") }
        write(File(paths.guideDir(tag, oldBase), "guide.pdf"), "old-guide")
        write(File(paths.guideDir(tag, newBase), "existing.pdf"), "target-guide")

        val result = renamer(root).rename(rom, newBase, tag)

        assertFalse(result.success)
        assertEquals(AtomicRename.RenameError.ALREADY_EXISTS, result.error)
        assertTrue(rom.exists())
        assertFalse(File(root, "Roms/$tag/$newBase.bin").exists())
        assertEquals("old-guide", File(paths.guideDir(tag, oldBase), "guide.pdf").readText())
        assertEquals("target-guide", File(paths.guideDir(tag, newBase), "existing.pdf").readText())
        assertTrue(
            "collision must be detected before anything is backed up",
            File(root, "Backup/$tag").listFiles()?.isEmpty() ?: true
        )
    }

    @Test fun `override cfg collision at the target rolls back the whole rename without backing anything up`() {
        val root = tmp.root
        val tag = "PS"
        val oldBase = "Old Game"
        val newBase = "New Game"
        val paths = CannoliPaths(root)
        val rom = File(root, "Roms/$tag/$oldBase.bin").apply { parentFile?.mkdirs(); writeText("rom") }
        write(paths.gameOverrideCfg(tag, oldBase, "nestopia"), "old-cfg")
        write(paths.gameOverrideCfg(tag, newBase, "nestopia"), "target-cfg")

        val result = renamer(root).rename(rom, newBase, tag)

        assertFalse(result.success)
        assertEquals(AtomicRename.RenameError.ALREADY_EXISTS, result.error)
        assertTrue(rom.exists())
        assertFalse(File(root, "Roms/$tag/$newBase.bin").exists())
        assertEquals("old-cfg", paths.gameOverrideCfg(tag, oldBase, "nestopia").readText())
        assertEquals("target-cfg", paths.gameOverrideCfg(tag, newBase, "nestopia").readText())
        assertTrue(
            "collision must be detected before anything is backed up",
            File(root, "Backup/$tag").listFiles()?.isEmpty() ?: true
        )
    }

    @Test fun `rename never touches system global or custom cfg`() {
        val root = tmp.root
        val tag = "PS"
        val paths = CannoliPaths(root)
        val rom = File(root, "Roms/$tag/Old.bin").apply { parentFile?.mkdirs(); writeText("rom") }
        write(paths.systemOverrideCfg(tag, "nestopia"), "system-scoped")
        write(paths.globalOverrideCfg, "global-scoped")
        write(paths.customCfg, "custom-scoped")

        val result = renamer(root).rename(rom, "New", tag)

        assertTrue(result.success)
        assertEquals("system-scoped", paths.systemOverrideCfg(tag, "nestopia").readText())
        assertEquals("global-scoped", paths.globalOverrideCfg.readText())
        assertEquals("custom-scoped", paths.customCfg.readText())
    }

    @Test fun `rename is a no-op for content when there is none to move`() {
        val root = tmp.root
        val rom = File(root, "Roms/PS/Old.bin").apply { parentFile?.mkdirs(); writeText("rom") }

        val result = renamer(root).rename(rom, "New", "PS")

        assertTrue(result.success)
    }
}
