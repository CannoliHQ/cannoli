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
}
