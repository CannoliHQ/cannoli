package dev.cannoli.scorza.library

import androidx.sqlite.SQLiteStatement
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.query
import dev.cannoli.scorza.db.transaction
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType

class AppsRepository(private val db: CannoliDatabase) {
    fun all(type: AppType? = null): List<App> {
        val sql = if (type != null) {
            "SELECT $COLUMNS FROM apps WHERE type = ? ORDER BY sort_order, display_name COLLATE NOCASE"
        } else {
            "SELECT $COLUMNS FROM apps ORDER BY type, sort_order, display_name COLLATE NOCASE"
        }
        val out = mutableListOf<App>()
        db.conn.query(sql) { stmt ->
            if (type != null) stmt.bindText(1, type.name)
            while (stmt.step()) out.add(rowToApp(stmt))
        }
        return out
    }

    fun byId(appId: Long): App? = db.conn.query("SELECT $COLUMNS FROM apps WHERE id = ?") { stmt ->
        stmt.bindLong(1, appId)
        if (stmt.step()) rowToApp(stmt) else null
    }

    fun byDisplayName(type: AppType, displayName: String): App? = db.conn.query(
        "SELECT $COLUMNS FROM apps WHERE type = ? AND display_name = ?"
    ) { stmt ->
        stmt.bindText(1, type.name)
        stmt.bindText(2, displayName)
        if (stmt.step()) rowToApp(stmt) else null
    }

    fun byPackage(type: AppType, packageName: String): App? = db.conn.query(
        "SELECT $COLUMNS FROM apps WHERE type = ? AND package_name = ?"
    ) { stmt ->
        stmt.bindText(1, type.name)
        stmt.bindText(2, packageName)
        if (stmt.step()) rowToApp(stmt) else null
    }

    fun count(type: AppType): Int = db.conn.query("SELECT COUNT(*) FROM apps WHERE type = ?") { stmt ->
        stmt.bindText(1, type.name)
        stmt.step()
        stmt.getInt(0)
    }

    fun upsert(type: AppType, displayName: String, packageName: String): Long {
        db.conn.execute(
            """
            INSERT INTO apps (type, display_name, package_name) VALUES (?, ?, ?)
            ON CONFLICT(type, package_name) DO UPDATE SET display_name = excluded.display_name
            """.trimIndent(),
            type.name, displayName, packageName,
        )
        return byPackage(type, packageName)!!.id
    }

    fun delete(appId: Long) = db.conn.execute("DELETE FROM apps WHERE id = ?", appId)

    fun setOrder(type: AppType, orderedIds: List<Long>) = db.conn.transaction {
        orderedIds.forEachIndexed { index, id ->
            db.conn.execute("UPDATE apps SET sort_order = ? WHERE id = ?", index.toLong(), id)
        }
    }

    private fun rowToApp(stmt: SQLiteStatement): App = App(
        id = stmt.getLong(0),
        type = AppType.valueOf(stmt.getText(1)),
        displayName = stmt.getText(2),
        packageName = stmt.getText(3),
        lastPlayedAt = if (stmt.isNull(4)) null else stmt.getLong(4),
    )

    private companion object {
        const val COLUMNS = "id, type, display_name, package_name, last_played_at"
    }
}
