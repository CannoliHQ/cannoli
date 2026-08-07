package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * force_softcore rides on the roms row, so it must survive the migration with a default that
 * matches today's behavior: nothing forces softcore until the user says so.
 */
class ForceSoftcoreMigrationTest {

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

    private fun forceSoftcoreOf(conn: SQLiteConnection, romId: Long): Int =
        conn.prepare("SELECT force_softcore FROM roms WHERE id = ?").use { stmt ->
            stmt.bindLong(1, romId)
            stmt.step()
            stmt.getInt(0)
        }

    @Test fun `existing rows default to off`() {
        val conn = v12Connection("defaults")
        Migrations.applyFrom(conn, 12)
        assertEquals(0, forceSoftcoreOf(conn, 1))
        conn.close()
    }

    @Test fun `the flag persists once set`() {
        val conn = v12Connection("persist")
        Migrations.applyFrom(conn, 12)
        conn.execSQL("UPDATE roms SET force_softcore = 1 WHERE id = 1")
        assertEquals(1, forceSoftcoreOf(conn, 1))
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
