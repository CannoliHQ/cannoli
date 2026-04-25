package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteStatement
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType

class AppsRepository(private val db: CannoliDatabase) {
    fun all(type: AppType? = null): List<App> = if (type != null) {
        db.conn.queryAll(
            "SELECT $COLUMNS FROM apps WHERE type = ? ORDER BY sort_order, display_name COLLATE NOCASE",
            type.name,
            mapper = ::rowToApp,
        )
    } else {
        db.conn.queryAll(
            "SELECT $COLUMNS FROM apps ORDER BY type, sort_order, display_name COLLATE NOCASE",
            mapper = ::rowToApp,
        )
    }

    fun byId(appId: Long): App? = db.conn.queryOne(
        "SELECT $COLUMNS FROM apps WHERE id = ?", appId, mapper = ::rowToApp,
    )

    fun byPackage(type: AppType, packageName: String): App? = db.conn.queryOne(
        "SELECT $COLUMNS FROM apps WHERE type = ? AND package_name = ?",
        type.name, packageName, mapper = ::rowToApp,
    )

    fun count(type: AppType): Int = db.conn.queryOne(
        "SELECT COUNT(*) FROM apps WHERE type = ?", type.name,
    ) { it.getInt(0) } ?: 0

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
