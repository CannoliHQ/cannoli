package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.ConfigLayoutMigration
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ScanLog
import java.io.File

class CannoliDatabase(private val pathsProvider: CannoliPathsProvider) {
    private val databaseFile: File get() = CannoliPaths(pathsProvider.root).database

    private var openConn: SQLiteConnection? = null
    private var openPath: String? = null

    // Open on first access. Hilt eagerly resolves @Singleton injects at MainActivity onCreate,
    // which is before the user clears the permission gate, so deferring the SQLite open keeps
    // construction free of file I/O (and lets the SD-card root resolved by setup win).
    val conn: SQLiteConnection get() = synchronized(this) {
        // Before the path is resolved, not after: the layout migration moves this file and its
        // -wal, and startStorageDependent() can reach a database before boot's own call runs.
        ConfigLayoutMigration.runOnce(pathsProvider.root)
        val dbFile = databaseFile
        val path = dbFile.absolutePath
        val existing = openConn
        // An early touch can open the database at the fallback root before setup resolves the real
        // one, so follow the current path instead of staying pinned to the first one opened.
        if (existing != null && openPath == path) return@synchronized existing
        existing?.close()
        openConn = null
        openPath = null
        dbFile.parentFile?.mkdirs()
        val c = BundledSQLiteDriver().open(path)
        c.execSQL("PRAGMA foreign_keys = ON")
        c.execSQL("PRAGMA journal_mode = WAL")
        runMigrations(c)
        runIntegrityCheck(c)
        c.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        openConn = c
        openPath = path
        c
    }

    /**
     * Serializes access to the underlying connection. BundledSQLiteDriver
     * connections are not safe for concurrent use across threads, so every
     * code path that touches [conn] must do so inside this lock (directly or
     * via the [CannoliDatabase] extension helpers in SqlExt). The monitor is
     * reentrant, so transactions can call locked helpers without deadlocking.
     */
    inline fun <T> withConn(block: (SQLiteConnection) -> T): T = synchronized(this) { block(conn) }

    fun close() = synchronized(this) {
        openConn?.close()
        openConn = null
        openPath = null
    }

    private fun runMigrations(conn: SQLiteConnection) {
        val current = readUserVersion(conn)
        if (current >= Migrations.current) return
        ScanLog.startRun("schema migration v$current -> v${Migrations.current}")
        Migrations.applyFrom(conn, current)
        ScanLog.write("schema migration complete")
    }

    private fun runIntegrityCheck(conn: SQLiteConnection) {
        val integrity = conn.query("PRAGMA integrity_check") { stmt ->
            stmt.step()
            stmt.getText(0)
        }
        if (integrity != "ok") {
            ScanLog.write("ERROR integrity_check returned: $integrity")
            throw DatabaseCorrupt("integrity_check returned: $integrity")
        }
        val fkViolations = conn.query("PRAGMA foreign_key_check") { stmt -> stmt.step() }
        if (fkViolations) {
            ScanLog.write("ERROR foreign_key_check reported violations")
            throw DatabaseCorrupt("foreign_key_check reported violations")
        }
    }

    private fun readUserVersion(conn: SQLiteConnection): Int =
        conn.query("PRAGMA user_version") { stmt ->
            stmt.step()
            stmt.getInt(0)
        }
}


