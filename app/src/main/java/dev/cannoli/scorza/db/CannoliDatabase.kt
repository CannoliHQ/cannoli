package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.util.ScanLog
import java.io.File

class CannoliDatabase(cannoliRoot: File) {
    private val dbFile = File(cannoliRoot, "Config/cannoli.db").apply { parentFile?.mkdirs() }
    private val driver = BundledSQLiteDriver()
    val conn: SQLiteConnection = driver.open(dbFile.absolutePath).also { open ->
        open.execSQL("PRAGMA foreign_keys = ON")
        open.execSQL("PRAGMA journal_mode = WAL")
        runMigrations(open)
        runIntegrityCheck(open)
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
        val ok = conn.prepare("PRAGMA integrity_check").use { stmt ->
            stmt.step()
            stmt.getText(0) == "ok"
        }
        if (!ok) ScanLog.write("WARN integrity_check did not return ok")

        val fkOk = conn.prepare("PRAGMA foreign_key_check").use { stmt -> !stmt.step() }
        if (!fkOk) ScanLog.write("WARN foreign_key_check reported violations")
    }

    private fun readUserVersion(conn: SQLiteConnection): Int =
        conn.prepare("PRAGMA user_version").use { stmt ->
            stmt.step()
            stmt.getInt(0)
        }
}
