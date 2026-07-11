package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveBackupManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeSave() {
        File(tmp.root, "Saves/SNES").mkdirs()
        File(tmp.root, "Saves/SNES/Mario.srm").writeBytes("LOCAL".toByteArray())
    }

    private fun backupDir() = File(tmp.root, "Backup/SaveSync/SNES/Mario")
    private fun manager() = SaveBackupManager(tmp.root, LocalSaveResolver(tmp.root))

    @Test fun backup_writes_zip() {
        writeSave()
        manager().backup("SNES", "Mario", keepCount = 5, stamp = 1000L)
        assertTrue(File(backupDir(), "1000.zip").isFile)
    }

    @Test fun backup_disabled_when_count_zero() {
        writeSave()
        manager().backup("SNES", "Mario", keepCount = 0, stamp = 1000L)
        assertFalse(backupDir().exists())
    }

    @Test fun backup_noop_when_no_local_save() {
        manager().backup("SNES", "Mario", keepCount = 5, stamp = 1000L)
        assertFalse(File(backupDir(), "1000.zip").exists())
    }

    @Test fun prune_keeps_newest_n() {
        writeSave()
        val m = manager()
        for (s in 1L..7L) m.backup("SNES", "Mario", keepCount = 3, stamp = s)
        val remaining = backupDir().listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("5.zip", "6.zip", "7.zip"), remaining)
    }

    @Test fun list_returns_backups_newest_first() {
        writeSave()
        val m = manager()
        m.backup("SNES", "Mario", keepCount = 5, stamp = 1000L)
        m.backup("SNES", "Mario", keepCount = 5, stamp = 3000L)
        m.backup("SNES", "Mario", keepCount = 5, stamp = 2000L)
        assertEquals(listOf(3000L, 2000L, 1000L), m.list("SNES", "Mario").map { it.stamp })
    }

    @Test fun listGames_reports_games_with_backups() {
        writeSave()
        File(tmp.root, "Saves/GBA").mkdirs()
        File(tmp.root, "Saves/GBA/Pokemon.srm").writeBytes("SAVE".toByteArray())
        val m = manager()
        m.backup("SNES", "Mario", keepCount = 5, stamp = 1000L)
        m.backup("GBA", "Pokemon", keepCount = 5, stamp = 2000L)
        val games = m.listGames()
        assertEquals(listOf("GBA" to "Pokemon", "SNES" to "Mario"), games.map { it.tag to it.base })
        assertEquals(1, games.first { it.base == "Mario" }.count)
    }

    @Test fun restore_overwrites_current_and_backs_it_up() {
        writeSave() // "LOCAL"
        val m = manager()
        m.backup("SNES", "Mario", keepCount = 5, stamp = 1000L) // snapshot of "LOCAL"
        File(tmp.root, "Saves/SNES/Mario.srm").writeBytes("CHANGED".toByteArray())

        val ok = m.restore("SNES", "Mario", stamp = 1000L, keepCount = 5)

        assertTrue(ok)
        assertEquals("LOCAL", File(tmp.root, "Saves/SNES/Mario.srm").readText())
        // the pre-restore "CHANGED" save was backed up, so we now have two snapshots + none lost
        assertTrue(m.list("SNES", "Mario").size >= 2)
    }

    @Test fun restore_returns_false_for_missing_backup() {
        writeSave()
        assertFalse(manager().restore("SNES", "Mario", stamp = 999L, keepCount = 5))
    }
}
