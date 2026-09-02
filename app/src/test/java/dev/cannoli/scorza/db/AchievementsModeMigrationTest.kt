package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ra_hardcore rides on the roms row and is nullable, because a game saying nothing is a third
 * answer rather than a default: it follows the global mode, and has to be able to go back to that.
 */
class AchievementsModeMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun v12Connection(name: String): SQLiteConnection {
        val file = File(tmp.root, "$name.db")
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        conn.execSQL(
            """
            CREATE TABLE roms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                platform_tag TEXT NOT NULL,
                display_name TEXT NOT NULL,
                ra_game_id INTEGER
            )
            """.trimIndent(),
        )
        conn.execSQL("INSERT INTO roms (id, path, platform_tag, display_name) VALUES (1, 'NES/a.nes', 'NES', 'A')")
        conn.execSQL("PRAGMA user_version = 12")
        return conn
    }

    /** Null and 0 are different answers here, so the reader has to be able to tell them apart. */
    private fun modeOf(conn: SQLiteConnection, romId: Long): Int? =
        conn.prepare("SELECT ra_hardcore FROM roms WHERE id = ?").use { stmt ->
            stmt.bindLong(1, romId)
            stmt.step()
            if (stmt.isNull(0)) null else stmt.getInt(0)
        }

    @Test fun `existing rows state no mode of their own`() {
        val conn = v12Connection("defaults")
        Migrations.applyFrom(conn, 12)
        assertNull(modeOf(conn, 1))
        conn.close()
    }

    @Test fun `all three answers persist`() {
        val conn = v12Connection("persist")
        Migrations.applyFrom(conn, 12)
        conn.execSQL("UPDATE roms SET ra_hardcore = 1 WHERE id = 1")
        assertEquals(1, modeOf(conn, 1))
        conn.execSQL("UPDATE roms SET ra_hardcore = 0 WHERE id = 1")
        assertEquals(0, modeOf(conn, 1))
        conn.execSQL("UPDATE roms SET ra_hardcore = NULL WHERE id = 1")
        assertNull(modeOf(conn, 1))
        conn.close()
    }

    @Test fun `the migration bumps the schema version`() {
        val conn = v12Connection("version")
        Migrations.applyFrom(conn, 12)
        conn.prepare("PRAGMA user_version").use {
            it.step()
            assertEquals(Migrations.current, it.getInt(0))
        }
        conn.close()
    }
}
