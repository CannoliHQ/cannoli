package dev.cannoli.scorza.input.v2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyInputMigrationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.root

    private fun migration(timestamp: String = "20260428T120000Z") =
        LegacyInputMigration(root(), clock = { timestamp })

    @Test
    fun runIfNeeded_with_no_legacy_state_is_noop() {
        assertFalse(migration().runIfNeeded())
        assertFalse(File(root(), "Config/Backup").exists())
    }

    @Test
    fun profiles_directory_is_moved_to_backup() {
        val profiles = File(root(), "Config/Profiles")
        profiles.mkdirs()
        File(profiles, "Cannoli Navigation.ini").writeText("[binding]\nbtn_south=96\n")
        File(profiles, "Default Controls.ini").writeText("[binding]\nbtn_south=96\n")

        assertTrue(migration().runIfNeeded())

        assertFalse(profiles.exists())
        val backup = File(root(), "Config/Backup/input_rewrite_20260428T120000Z/Profiles")
        assertTrue(backup.isDirectory)
        assertTrue(File(backup, "Cannoli Navigation.ini").isFile)
        assertTrue(File(backup, "Default Controls.ini").isFile)
    }

    @Test
    fun override_with_controller_type_is_copied_to_backup_before_stripping() {
        val systemsDir = File(root(), "Config/Overrides/systems")
        systemsDir.mkdirs()
        val override = File(systemsDir, "snes.ini")
        override.writeText(
            "controller_type=513\n" +
                "[frontend]\n" +
                "screen_effect=NONE\n"
        )

        assertTrue(migration().runIfNeeded())

        val backup = File(root(), "Config/Backup/input_rewrite_20260428T120000Z/Config/Overrides/systems/snes.ini")
        assertTrue(backup.isFile)
        assertTrue(backup.readText().contains("controller_type=513"))

        val live = override.readText()
        assertFalse(live.contains("controller_type"))
        assertTrue(live.contains("[frontend]"))
        assertTrue(live.contains("screen_effect=NONE"))
    }

    @Test
    fun controller_type_line_stripped_preserves_other_content() {
        val gamesDir = File(root(), "Config/Overrides/Games/snes")
        gamesDir.mkdirs()
        val override = File(gamesDir, "Super Mario World.ini")
        override.writeText(
            "[frontend]\n" +
                "screen_effect=SHADER\n" +
                "controller_type=257\n" +
                "[runtime]\n" +
                "save_slot=3\n"
        )

        assertTrue(migration().runIfNeeded())

        val live = override.readText()
        assertFalse(live.contains("controller_type"))
        assertTrue(live.contains("[frontend]"))
        assertTrue(live.contains("screen_effect=SHADER"))
        assertTrue(live.contains("[runtime]"))
        assertTrue(live.contains("save_slot=3"))
    }

    @Test
    fun rerun_after_migration_is_noop() {
        val profiles = File(root(), "Config/Profiles")
        profiles.mkdirs()
        File(profiles, "x.ini").writeText("[binding]\n")

        assertTrue(migration().runIfNeeded())
        assertFalse(migration().runIfNeeded())
    }
}
