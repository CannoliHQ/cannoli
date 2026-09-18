package dev.cannoli.scorza.saves

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var paths: CannoliPaths
    private lateinit var migration: SaveMigration

    @Before fun setUp() {
        root = tmp.newFolder("cannoli")
        paths = CannoliPaths(root)
        migration = SaveMigration(paths) { 1_700_000_000_000L }
    }

    private fun loose(tag: String, name: String, body: String = "x"): File =
        File(paths.savesFor(tag).apply { mkdirs() }, name).apply { writeText(body) }

    @Test fun `a single save moves into the game folder`() {
        val srm = loose("GBA", "Pokemon.srm", "SAVE")

        val result = migration.migrateGame("GBA", "Pokemon")

        assertEquals(SaveMigration.Outcome.MOVED, result.outcome)
        assertFalse(srm.exists())
        assertEquals("SAVE", File(paths.saveDirFor("GBA", "Pokemon"), "Pokemon.srm").readText())
    }

    /** VBA-M writes the real-time clock beside the save; both belong to the game. */
    @Test fun `sibling files move together`() {
        loose("GBA", "Pokemon.srm", "SAVE")
        loose("GBA", "Pokemon.rtc", "RTC")

        assertEquals(2, migration.migrateGame("GBA", "Pokemon").moved)

        val dir = paths.saveDirFor("GBA", "Pokemon")
        assertEquals("SAVE", File(dir, "Pokemon.srm").readText())
        assertEquals("RTC", File(dir, "Pokemon.rtc").readText())
    }

    /** mupen writes each save type as its own file sharing the rom's base name. */
    @Test fun `every n64 save type moves`() {
        listOf("sra", "eep", "fla", "mpk").forEach { loose("N64", "Zelda.$it") }

        assertEquals(4, migration.migrateGame("N64", "Zelda").moved)
        assertEquals(4, paths.saveDirFor("N64", "Zelda").listFiles()!!.size)
    }

    @Test fun `a save that belongs to another game is left alone`() {
        loose("GBA", "Pokemon.srm")
        val other = loose("GBA", "Zelda.srm")

        migration.migrateGame("GBA", "Pokemon")

        assertTrue(other.exists())
        assertFalse(File(paths.saveDirFor("GBA", "Pokemon"), "Zelda.srm").exists())
    }

    @Test fun `every moved file is backed up first`() {
        loose("GBA", "Pokemon.srm", "SAVE")
        loose("GBA", "Pokemon.rtc", "RTC")

        migration.migrateGame("GBA", "Pokemon")

        // Located rather than named: the stamp is formatted in the device's zone, so hardcoding it
        // would pass only where the test was written.
        val root = paths.backupDir.listFiles()!!.single { it.name.startsWith("save-migration-") }
        val backup = File(root, "GBA/Pokemon")
        assertEquals("SAVE", File(backup, "Pokemon.srm").readText())
        assertEquals("RTC", File(backup, "Pokemon.rtc").readText())
    }

    /** A collision is something the migration did not predict, so it stops rather than guessing. */
    @Test fun `a name already at the destination moves nothing`() {
        loose("GBA", "Pokemon.srm", "LOOSE")
        File(paths.saveDirFor("GBA", "Pokemon").apply { mkdirs() }, "Pokemon.srm").writeText("EXISTING")

        val result = migration.migrateGame("GBA", "Pokemon")

        assertEquals(SaveMigration.Outcome.COLLIDED, result.outcome)
        assertEquals("LOOSE", File(paths.savesFor("GBA"), "Pokemon.srm").readText())
        assertEquals("EXISTING", File(paths.saveDirFor("GBA", "Pokemon"), "Pokemon.srm").readText())
    }

    @Test fun `running twice moves nothing the second time`() {
        loose("GBA", "Pokemon.srm")

        assertEquals(SaveMigration.Outcome.MOVED, migration.migrateGame("GBA", "Pokemon").outcome)
        assertEquals(SaveMigration.Outcome.NOTHING_TO_DO, migration.migrateGame("GBA", "Pokemon").outcome)
    }

    @Test fun `a game with no saves is not given an empty folder`() {
        paths.savesFor("GBA").mkdirs()

        assertEquals(SaveMigration.Outcome.NOTHING_TO_DO, migration.migrateGame("GBA", "Pokemon").outcome)
        assertFalse(paths.saveDirFor("GBA", "Pokemon").exists())
    }

    @Test fun `an already migrated folder save is left where it is`() {
        val dir = paths.saveDirFor("PSP", "God of War").apply { mkdirs() }
        File(dir, "data.bin").writeText("SAVE")

        assertEquals(SaveMigration.Outcome.NOTHING_TO_DO, migration.migrateGame("PSP", "God of War").outcome)
        assertEquals("SAVE", File(dir, "data.bin").readText())
    }

    /**
     * PPSSPP keeps one memory stick for the platform and files a game inside SAVEDATA by disc id.
     * Moving that into a per-game folder is what stranded it, so the migration leaves it alone.
     */
    @Test fun `a shared save root is left exactly as it is`() {
        val savedata = File(paths.savesFor("PSP"), "SAVEDATA/UCUS98653").apply { mkdirs() }
        File(savedata, "DATA.BIN").writeText("SAVE")
        loose("PSP", "God of War.srm", "STRAY")

        val result = migration.migrateGame("PSP", "God of War")

        assertEquals(SaveMigration.Outcome.NOTHING_TO_DO, result.outcome)
        assertEquals("SAVE", File(savedata, "DATA.BIN").readText())
        assertEquals("STRAY", File(paths.savesFor("PSP"), "God of War.srm").readText())
        assertFalse(paths.saveDirFor("PSP", "God of War").exists())
    }

    @Test fun `every move is written to the manifest`() {
        loose("GBA", "Pokemon.srm")

        migration.migrateGame("GBA", "Pokemon")

        val manifest = File(paths.configState, "save_migration.log").readText()
        assertTrue(manifest.contains("Pokemon.srm"))
        assertTrue(manifest.contains("->"))
    }
}
