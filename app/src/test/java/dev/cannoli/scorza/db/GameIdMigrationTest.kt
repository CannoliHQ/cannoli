package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sigil columns ride on the roms row, and sigil_probe is the one that has to be nullable: null
 * is the row the drain has still to reach, and anything else is an attempt that will not be made
 * twice whether or not it produced an id.
 */
class GameIdMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun v13Connection(name: String): SQLiteConnection {
        val conn = BundledSQLiteDriver().open(File(tmp.root, "$name.db").absolutePath)
        conn.execSQL(
            """
            CREATE TABLE roms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                platform_tag TEXT NOT NULL,
                display_name TEXT NOT NULL,
                ra_hardcore INTEGER
            )
            """.trimIndent(),
        )
        conn.execSQL("INSERT INTO roms (id, path, platform_tag, display_name) VALUES (1, 'PS2/a.iso', 'PS2', 'A')")
        conn.execSQL("PRAGMA user_version = 13")
        return conn
    }

    private fun columns(conn: SQLiteConnection): List<String> =
        conn.prepare("PRAGMA table_info(roms)").use { stmt ->
            buildList { while (stmt.step()) add(stmt.getText(1)) }
        }

    @Test fun `the seven columns arrive`() {
        val conn = v13Connection("columns")
        Migrations.applyFrom(conn, 13)
        val cols = columns(conn)
        listOf(
            "sigil_title_id", "sigil_save_id", "sigil_raw_serial",
            "sigil_usage", "sigil_source", "sigil_experimental", "sigil_probe",
        ).forEach { assertTrue("$it missing", it in cols) }
        conn.close()
    }

    @Test fun `an existing rom is unprobed rather than probed and empty`() {
        val conn = v13Connection("unprobed")
        Migrations.applyFrom(conn, 13)
        conn.prepare("SELECT sigil_probe, sigil_experimental FROM roms WHERE id = 1").use { stmt ->
            stmt.step()
            assertTrue(stmt.isNull(0))
            assertEquals(0, stmt.getInt(1))
        }
        conn.close()
    }

    @Test fun `the pending index covers only rows still to do`() {
        val conn = v13Connection("index")
        Migrations.applyFrom(conn, 13)
        val sql = conn.prepare("SELECT sql FROM sqlite_master WHERE name = 'roms_sigil_pending'").use { stmt ->
            if (stmt.step()) stmt.getText(0) else null
        }
        assertTrue(sql != null && sql.contains("WHERE sigil_probe IS NULL"))
        conn.close()
    }

    @Test fun `migrating leaves the schema at fourteen`() {
        val conn = v13Connection("version")
        Migrations.applyFrom(conn, 13)
        conn.prepare("PRAGMA user_version").use { stmt ->
            stmt.step()
            assertEquals(14, stmt.getInt(0))
        }
        assertEquals(14, Migrations.current)
        conn.close()
    }

    @Test fun `applying twice is not an error`() {
        val conn = v13Connection("twice")
        Migrations.applyFrom(conn, 13)
        Migrations.applyFrom(conn, 14)
        assertNull(null)
        conn.close()
    }
}
