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
 * Migration 12 rewrites game_overrides, which for a user who ran the SQLite cutover and has not
 * edited an override since holds the only surviving copy of their per-game picks. It converts the
 * legacy runner caption into an EmulatorSource name, so every caption shape needs a pinned result.
 */
class GameOverridesMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun v11Connection(name: String): SQLiteConnection {
        val file = File(tmp.root, "$name.db")
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        conn.execSQL("PRAGMA foreign_keys = ON")
        conn.execSQL("CREATE TABLE platforms (tag TEXT PRIMARY KEY, display_name TEXT NOT NULL)")
        conn.execSQL(
            """
            CREATE TABLE roms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                platform_tag TEXT NOT NULL
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE TABLE game_overrides (
                rom_id INTEGER PRIMARY KEY REFERENCES roms(id) ON DELETE CASCADE,
                core_id TEXT,
                runner TEXT,
                app_package TEXT
            )
            """.trimIndent(),
        )
        conn.execSQL("PRAGMA user_version = 11")
        return conn
    }

    private fun seed(conn: SQLiteConnection, romId: Long, core: String?, runner: String?, app: String?) {
        conn.execSQL("INSERT INTO roms (id, path, platform_tag) VALUES ($romId, 'NES/g$romId.nes', 'NES')")
        conn.prepare("INSERT INTO game_overrides (rom_id, core_id, runner, app_package) VALUES (?, ?, ?, ?)")
            .use { stmt ->
                stmt.bindLong(1, romId)
                if (core == null) stmt.bindNull(2) else stmt.bindText(2, core)
                if (runner == null) stmt.bindNull(3) else stmt.bindText(3, runner)
                if (app == null) stmt.bindNull(4) else stmt.bindText(4, app)
                stmt.step()
            }
    }

    private fun sourceOf(conn: SQLiteConnection, romId: Long): String =
        conn.prepare("SELECT source FROM game_overrides WHERE rom_id = ?").use { stmt ->
            stmt.bindLong(1, romId)
            stmt.step()
            stmt.getText(0)
        }

    @Test fun `every legacy runner caption converts to the right source`() {
        val conn = v11Connection("caps")
        seed(conn, 1, "nestopia_libretro", "Internal", null)
        seed(conn, 2, "nestopia_libretro", "RicottaArch", null)
        seed(conn, 3, "nestopia_libretro", "RetroArch (Unknown)", null)
        seed(conn, 4, "nestopia_libretro", "com.retroarch.ra32", null)
        seed(conn, 5, null, "Standalone", "com.fastemulator.gba")
        seed(conn, 6, null, "App", "com.fastemulator.gba")
        seed(conn, 7, null, null, "com.fastemulator.gba")
        seed(conn, 8, "nestopia_libretro", null, null)

        Migrations.applyFrom(conn, 11)

        assertEquals("Internal", sourceOf(conn, 1))
        assertEquals("RetroArch", sourceOf(conn, 2))
        assertEquals("RetroArch", sourceOf(conn, 3))
        assertEquals("RetroArch", sourceOf(conn, 4))
        assertEquals("Standalone", sourceOf(conn, 5))
        assertEquals("Standalone", sourceOf(conn, 6))
        assertEquals("Standalone", sourceOf(conn, 7))
        assertEquals("RetroArch", sourceOf(conn, 8))
        conn.close()
    }

    @Test fun `the core id and app package survive the rewrite`() {
        val conn = v11Connection("payload")
        seed(conn, 1, "nestopia_libretro", "RicottaArch", null)
        seed(conn, 2, null, "Standalone", "com.fastemulator.gba")

        Migrations.applyFrom(conn, 11)

        conn.prepare("SELECT core_id, app_package FROM game_overrides WHERE rom_id = 1").use {
            it.step()
            assertEquals("nestopia_libretro", it.getText(0))
        }
        conn.prepare("SELECT core_id, app_package FROM game_overrides WHERE rom_id = 2").use {
            it.step()
            assertEquals("com.fastemulator.gba", it.getText(1))
        }
        conn.close()
    }

    @Test fun `no override rows survive a migration with nothing to convert`() {
        val conn = v11Connection("empty")
        Migrations.applyFrom(conn, 11)
        conn.prepare("SELECT COUNT(*) FROM game_overrides").use {
            it.step()
            assertEquals(0, it.getInt(0))
        }
        conn.close()
    }

    @Test fun `the cascade still works after the rewrite`() {
        val conn = v11Connection("cascade")
        seed(conn, 1, "nestopia_libretro", "Internal", null)
        Migrations.applyFrom(conn, 11)
        conn.execSQL("DELETE FROM roms WHERE id = 1")
        conn.prepare("SELECT COUNT(*) FROM game_overrides").use {
            it.step()
            assertEquals(0, it.getInt(0))
        }
        conn.close()
    }
}
