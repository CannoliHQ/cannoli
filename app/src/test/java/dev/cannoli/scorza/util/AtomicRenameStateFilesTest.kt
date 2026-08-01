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

class AtomicRenameStateFilesTest {

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
        return AtomicRename(root, RomDirectoryWalker(paths, assets, arcade))
    }

    @Test fun `rename renames state dir AND the files inside it`() {
        val root = tmp.root
        val tag = "SNES"
        val old = "Old Game"
        val new = "New Game"
        val rom = File(root, "Roms/$tag/$old.sfc").apply { parentFile?.mkdirs(); writeText("rom") }
        write(File(root, "Save States/$tag/$old/$old.state"), "S")
        write(File(root, "Save States/$tag/$old/$old.state.png"), "P")

        val result = renamer(root).rename(rom, new, tag)

        assertTrue(result.success)
        assertTrue(File(root, "Save States/$tag/$new/$new.state").exists())
        assertEquals("S", File(root, "Save States/$tag/$new/$new.state").readText())
        assertEquals("P", File(root, "Save States/$tag/$new/$new.state.png").readText())
        assertFalse(File(root, "Save States/$tag/$new/$old.state").exists())
    }
}
