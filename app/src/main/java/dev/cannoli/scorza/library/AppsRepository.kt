package dev.cannoli.scorza.library

import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType

class AppsRepository(private val db: CannoliDatabase) {
    fun all(type: AppType? = null): List<App> {
        val sql = if (type != null) {
            "SELECT id, type, display_name, package_name, last_played_at FROM apps WHERE type = ? ORDER BY sort_order, display_name COLLATE NOCASE"
        } else {
            "SELECT id, type, display_name, package_name, last_played_at FROM apps ORDER BY type, sort_order, display_name COLLATE NOCASE"
        }
        val out = mutableListOf<App>()
        db.conn.prepare(sql).use { stmt ->
            if (type != null) stmt.bindText(1, type.name)
            while (stmt.step()) out.add(rowToApp(stmt))
        }
        return out
    }

    fun byId(appId: Long): App? {
        db.conn.prepare("SELECT id, type, display_name, package_name, last_played_at FROM apps WHERE id = ?").use { stmt ->
            stmt.bindLong(1, appId)
            return if (stmt.step()) rowToApp(stmt) else null
        }
    }

    fun byPackage(type: AppType, packageName: String): App? {
        db.conn.prepare("SELECT id, type, display_name, package_name, last_played_at FROM apps WHERE type = ? AND package_name = ?").use { stmt ->
            stmt.bindText(1, type.name)
            stmt.bindText(2, packageName)
            return if (stmt.step()) rowToApp(stmt) else null
        }
    }

    fun count(type: AppType): Int {
        db.conn.prepare("SELECT COUNT(*) FROM apps WHERE type = ?").use { stmt ->
            stmt.bindText(1, type.name)
            stmt.step()
            return stmt.getInt(0)
        }
    }

    fun delete(appId: Long) {
        db.conn.prepare("DELETE FROM apps WHERE id = ?").use { stmt ->
            stmt.bindLong(1, appId)
            stmt.step()
        }
    }

    fun setOrder(type: AppType, orderedIds: List<Long>) {
        db.conn.execSQL("BEGIN")
        try {
            orderedIds.forEachIndexed { index, id ->
                db.conn.prepare("UPDATE apps SET sort_order = ? WHERE id = ? AND type = ?").use { stmt ->
                    stmt.bindLong(1, index.toLong())
                    stmt.bindLong(2, id)
                    stmt.bindText(3, type.name)
                    stmt.step()
                }
            }
            db.conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            db.conn.execSQL("ROLLBACK")
            throw t
        }
    }

    private fun rowToApp(stmt: androidx.sqlite.SQLiteStatement): App = App(
        id = stmt.getLong(0),
        type = AppType.valueOf(stmt.getText(1)),
        displayName = stmt.getText(2),
        packageName = stmt.getText(3),
        lastPlayedAt = if (stmt.isNull(4)) null else stmt.getLong(4),
    )
}
