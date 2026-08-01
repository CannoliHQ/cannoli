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

class AtomicRenameRelocateTest {

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

    @Test fun `relocate moves state dir inner files and srm to new base`() {
        val root = tmp.root
        val oldBase = "Inner Name (USA)"
        val newBase = "Outer"
        val tag = "SNES"
        write(File(root, "Save States/$tag/$oldBase/$oldBase.state"), "S")
        write(File(root, "Save States/$tag/$oldBase/$oldBase.state.auto"), "A")
        write(File(root, "Save States/$tag/$oldBase/$oldBase.state.png"), "P")
        write(File(root, "Saves/$tag/$oldBase.srm"), "R")

        val result = renamer(root).relocateSaveData(tag, oldBase, newBase)

        assertTrue(result.success)
        assertFalse(File(root, "Save States/$tag/$oldBase").exists())
        assertEquals("S", File(root, "Save States/$tag/$newBase/$newBase.state").readText())
        assertEquals("A", File(root, "Save States/$tag/$newBase/$newBase.state.auto").readText())
        assertEquals("P", File(root, "Save States/$tag/$newBase/$newBase.state.png").readText())
        assertEquals("R", File(root, "Saves/$tag/$newBase.srm").readText())
    }

    @Test fun `relocate is a noop when nothing exists under old base`() {
        val result = renamer(tmp.root).relocateSaveData("SNES", "Nope", "Other")
        assertTrue(result.success)
    }

    @Test fun `relocate moves srm when no state dir exists`() {
        val root = tmp.root
        write(File(root, "Saves/SNES/Old.srm"), "R")
        val result = renamer(root).relocateSaveData("SNES", "Old", "New")
        assertTrue(result.success)
        assertFalse(File(root, "Saves/SNES/Old.srm").exists())
        assertEquals("R", File(root, "Saves/SNES/New.srm").readText())
    }

    @Test fun `relocate succeeds when an empty target state dir exists`() {
        val root = tmp.root
        val tag = "SNES"
        write(File(root, "Save States/$tag/Old/Old.state"), "S")
        File(root, "Save States/$tag/New").mkdirs()
        val result = renamer(root).relocateSaveData(tag, "Old", "New")
        assertTrue(result.success)
        assertEquals("S", File(root, "Save States/$tag/New/New.state").readText())
    }

    @Test fun `relocate moves all matching save files not just srm`() {
        val root = tmp.root
        val tag = "GBC"
        write(File(root, "Saves/$tag/Old.srm"), "S")
        write(File(root, "Saves/$tag/Old.rtc"), "T")
        val result = renamer(root).relocateSaveData(tag, "Old", "New")
        assertTrue(result.success)
        assertEquals("S", File(root, "Saves/$tag/New.srm").readText())
        assertEquals("T", File(root, "Saves/$tag/New.rtc").readText())
        assertFalse(File(root, "Saves/$tag/Old.srm").exists())
        assertFalse(File(root, "Saves/$tag/Old.rtc").exists())
    }
}
