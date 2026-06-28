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
}
