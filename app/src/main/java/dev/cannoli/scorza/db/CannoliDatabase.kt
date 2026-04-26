package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.util.ScanLog
import java.io.File

class CannoliDatabase(cannoliRoot: File) {
    val conn: SQLiteConnection

    init {
        val dbFile = CannoliPaths(cannoliRoot).database.apply { parentFile?.mkdirs() }
        conn = BundledSQLiteDriver().open(dbFile.absolutePath)
        conn.execSQL("PRAGMA foreign_keys = ON")
        conn.execSQL("PRAGMA journal_mode = WAL")
        runMigrations(conn)
        runIntegrityCheck(conn)
        conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    fun close() {
        conn.close()
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


