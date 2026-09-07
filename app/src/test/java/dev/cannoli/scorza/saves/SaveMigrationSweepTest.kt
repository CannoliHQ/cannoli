package dev.cannoli.scorza.saves

import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.db.RomsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveMigrationSweepTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private val roms: RomsRepository = mockk()
    private lateinit var sweep: SaveMigrationSweep

    @Before fun setUp() {
        root = tmp.newFolder("cannoli")
        val provider = mockk<CannoliPathsProvider>()
        every { provider.root } returns root
        sweep = SaveMigrationSweep(provider, roms)
    }

    private fun rom(tag: String, base: String): Rom = mockk(relaxed = true) {
        every { platformTag } returns tag
        every { path } returns File(root, "Roms/$tag/$base.sfc")
    }

    private fun save(tag: String, name: String, body: String = "SAVE"): File =
        File(File(root, "Saves/$tag").apply { mkdirs() }, name).apply { writeText(body) }

    @Test fun `every game in the library gets its folder`() = runTest {
        save("SNES", "Mario.srm")
        save("GBA", "Pokemon.srm")
        save("GBA", "Pokemon.rtc")
        every { roms.allRoms() } returns listOf(rom("SNES", "Mario"), rom("GBA", "Pokemon"))

        assertEquals(2, sweep.runOnce())

        assertEquals("SAVE", File(root, "Saves/SNES/Mario/Mario.srm").readText())
        assertEquals("SAVE", File(root, "Saves/GBA/Pokemon/Pokemon.rtc").readText())
    }

    /**
     * The whole reason attribution comes from the rom rows. A file we cannot name an owner for is
     * left alone rather than filed under a guess.
     */
    @Test fun `a save with no game in the library is not touched`() = runTest {
        save("SNES", "Mario.srm")
        val orphan = save("SNES", "Something Else.srm", "ORPHAN")
        every { roms.allRoms() } returns listOf(rom("SNES", "Mario"))

        sweep.runOnce()

        assertTrue(orphan.isFile)
        assertEquals("ORPHAN", orphan.readText())
        assertFalse(File(root, "Saves/SNES/Something Else").exists())
    }

    @Test fun `the second run does no work`() = runTest {
        save("SNES", "Mario.srm")
        every { roms.allRoms() } returns listOf(rom("SNES", "Mario"))

        assertEquals(1, sweep.runOnce())
        save("SNES", "Mario.rtc", "LATE")

        assertEquals(0, sweep.runOnce())
        assertEquals("LATE", File(root, "Saves/SNES/Mario.rtc").readText())
    }

    @Test fun `a library that cannot be read leaves every save alone`() = runTest {
        val untouched = save("SNES", "Mario.srm")
        every { roms.allRoms() } throws IllegalStateException("db closed")

        assertEquals(0, sweep.runOnce())

        assertTrue(untouched.isFile)
    }
}
