package dev.cannoli.scorza.launcher

import android.content.res.AssetManager
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.scorza.util.RomDirectoryWalker
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveIdentityMigratorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, text: String) { f.parentFile?.mkdirs(); f.writeText(text) }

    private fun migrator(root: File): SaveIdentityMigrator {
        val romsDir = File(root, "Roms").also { it.mkdirs() }
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws FileNotFoundException()
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir
        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
        val walker = RomDirectoryWalker(paths, assets, arcade)
        val artwork = ArtworkLookup(paths)
        return SaveIdentityMigrator(root, AtomicRename(root, walker, artwork))
    }

    private fun makeZip(zipName: String, innerName: String): File {
        val zip = File(tmp.root, zipName)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(innerName)); out.write(ByteArray(32) { 1 }); out.closeEntry()
        }
        return zip
    }

    @Test fun `heals inner-named state and srm to the library name`() {
        val root = tmp.root
        val inner = "Legend of Zelda, The - A Link to the Past (USA)"
        val lib = "The Legend Of Zelda - A Link To The Past"
        write(File(root, "Save States/SNES/$inner/$inner.state"), "S")
        write(File(root, "Saves/SNES/$inner.srm"), "R")
        val zip = makeZip("$lib.zip", "$inner.sfc")

        migrator(root).migrateOnLaunch("SNES", lib, zip)

        assertEquals("S", File(root, "Save States/SNES/$lib/$lib.state").readText())
        assertEquals("R", File(root, "Saves/SNES/$lib.srm").readText())
        assertFalse(File(root, "Save States/SNES/$inner").exists())
    }

    @Test fun `skips when library-named state already exists`() {
        val root = tmp.root
        val inner = "Inner (USA)"
        val lib = "Outer"
        write(File(root, "Save States/SNES/$inner/$inner.state"), "OLD")
        write(File(root, "Save States/SNES/$lib/$lib.state"), "NEW")
        val zip = makeZip("$lib.zip", "$inner.sfc")

        migrator(root).migrateOnLaunch("SNES", lib, zip)

        assertTrue(File(root, "Save States/SNES/$inner").exists())
        assertEquals("NEW", File(root, "Save States/SNES/$lib/$lib.state").readText())
    }

    @Test fun `skips when library srm already exists`() {
        val root = tmp.root
        val inner = "Inner (USA)"
        val lib = "Outer"
        write(File(root, "Save States/SNES/$inner/$inner.state"), "OLD")
        write(File(root, "Saves/SNES/$lib.srm"), "NEW")
        val zip = makeZip("$lib.zip", "$inner.sfc")

        migrator(root).migrateOnLaunch("SNES", lib, zip)

        assertTrue(File(root, "Save States/SNES/$inner").exists())
        assertFalse(File(root, "Save States/SNES/$lib").exists())
    }

    @Test fun `no-op when inner name equals library name`() {
        val root = tmp.root
        val name = "Same Name"
        write(File(root, "Save States/SNES/$name/$name.state"), "S")
        val zip = makeZip("$name.zip", "$name.sfc")

        migrator(root).migrateOnLaunch("SNES", name, zip)

        assertEquals("S", File(root, "Save States/SNES/$name/$name.state").readText())
    }
}
