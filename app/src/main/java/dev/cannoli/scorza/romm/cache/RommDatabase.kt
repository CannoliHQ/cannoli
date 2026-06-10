package dev.cannoli.scorza.romm.cache

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.util.NaturalSort
import java.io.File

data class GameRecord(val game: RommGame, val updatedAt: String?)

class RommDatabase(private val dbFileProvider: () -> File) {

    private val conn: SQLiteConnection by lazy {
        val dbFile = dbFileProvider()
        dbFile.parentFile?.mkdirs()
        val c = BundledSQLiteDriver().open(dbFile.absolutePath)
        c.execSQL("PRAGMA journal_mode = WAL")
        ensureSchema(c)
        c
    }

    private inline fun <T> withConn(block: (SQLiteConnection) -> T): T = synchronized(this) { block(conn) }

    fun close() = synchronized(this) { conn.close() }

    private fun ensureSchema(c: SQLiteConnection) {
        val version = c.prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }
        if (version != SCHEMA_VERSION) {
            c.execSQL("DROP TABLE IF EXISTS platforms")
            c.execSQL("DROP TABLE IF EXISTS games")
            c.execSQL("DROP TABLE IF EXISTS sync_state")
        }
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS platforms (
                id INTEGER PRIMARY KEY,
                slug TEXT NOT NULL,
                cannoli_tag TEXT NOT NULL,
                display_name TEXT NOT NULL,
                rom_count INTEGER NOT NULL DEFAULT 0,
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT
            )
        """.trimIndent())
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS games (
                id INTEGER PRIMARY KEY,
                platform_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                fs_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                summary TEXT,
                revision TEXT,
                regions TEXT NOT NULL DEFAULT '[]',
                languages TEXT NOT NULL DEFAULT '[]',
                cover_path TEXT,
                files_json TEXT NOT NULL DEFAULT '[]',
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT
            )
        """.trimIndent())
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_games_platform_sort ON games(platform_id, sort_key)")
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """.trimIndent())
        c.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
    }

    fun platforms(): List<RommPlatform> = withConn { c ->
        c.queryAll("SELECT id, slug, cannoli_tag, display_name, rom_count FROM platforms ORDER BY sort_key") {
            RommPlatform(
                id = it.getInt(0),
                slug = it.getText(1),
                cannoliTag = it.getText(2),
                displayName = it.getText(3),
                romCount = it.getInt(4),
            )
        }
    }

    fun replacePlatforms(rows: List<Pair<RommPlatform, String?>>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            c.execSQL("DELETE FROM platforms")
            rows.forEach { (p, updatedAt) -> insertPlatform(c, p, updatedAt) }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun upsertPlatforms(rows: List<Pair<RommPlatform, String?>>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            rows.forEach { (p, updatedAt) -> insertPlatform(c, p, updatedAt) }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    private fun insertPlatform(c: SQLiteConnection, p: RommPlatform, updatedAt: String?) = c.execute(
        "INSERT OR REPLACE INTO platforms (id, slug, cannoli_tag, display_name, rom_count, sort_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        p.id, p.slug, p.cannoliTag, p.displayName, p.romCount, NaturalSort.toSortKey(p.displayName), updatedAt,
    )

    fun upsertGames(records: List<GameRecord>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            records.forEach { rec ->
                val g = rec.game
                c.execute(
                    """INSERT OR REPLACE INTO games
                       (id, platform_id, name, fs_name, size_bytes, summary, revision, regions, languages, cover_path, files_json, sort_key, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    g.id, g.platformId, g.name, g.fsName, g.sizeBytes, g.summary, g.revision,
                    RommCacheJson.encodeStrings(g.regions), RommCacheJson.encodeStrings(g.languages),
                    g.coverPath, RommCacheJson.encodeFiles(g.files), NaturalSort.toSortKey(g.name), rec.updatedAt,
                )
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun games(platformId: Int, search: String?, limit: Int, offset: Int): List<RommGame> = withConn { c ->
        val like = search?.takeIf { it.isNotBlank() }?.let { "%$it%" }
        val sql = buildString {
            append("SELECT id, platform_id, name, fs_name, size_bytes, summary, revision, regions, languages, cover_path, files_json FROM games WHERE platform_id = ?")
            if (like != null) append(" AND name LIKE ?")
            append(" ORDER BY sort_key LIMIT ? OFFSET ?")
        }
        val args: Array<Any?> = if (like != null) arrayOf(platformId, like, limit, offset) else arrayOf(platformId, limit, offset)
        c.queryAll(sql, *args) { stmt ->
            RommGame(
                id = stmt.getInt(0),
                platformId = stmt.getInt(1),
                name = stmt.getText(2),
                fsName = stmt.getText(3),
                sizeBytes = stmt.getLong(4),
                summary = if (stmt.isNull(5)) null else stmt.getText(5),
                revision = if (stmt.isNull(6)) null else stmt.getText(6),
                regions = RommCacheJson.decodeStrings(stmt.getText(7)),
                languages = RommCacheJson.decodeStrings(stmt.getText(8)),
                coverPath = if (stmt.isNull(9)) null else stmt.getText(9),
                files = RommCacheJson.decodeFiles(stmt.getText(10)),
            )
        }
    }

    fun gamesCount(platformId: Int, search: String?): Int = withConn { c ->
        val like = search?.takeIf { it.isNotBlank() }?.let { "%$it%" }
        val sql = if (like != null) "SELECT COUNT(*) FROM games WHERE platform_id = ? AND name LIKE ?"
        else "SELECT COUNT(*) FROM games WHERE platform_id = ?"
        val args: Array<Any?> = if (like != null) arrayOf(platformId, like) else arrayOf(platformId)
        c.queryOne(sql, *args) { it.getInt(0) } ?: 0
    }

    fun gameCountsByPlatform(): Map<Int, Int> = withConn { c ->
        c.queryAll("SELECT platform_id, COUNT(*) FROM games GROUP BY platform_id") { it.getInt(0) to it.getInt(1) }.toMap()
    }

    fun cachedGameIds(platformId: Int): Set<Int> = withConn { c ->
        c.queryAll("SELECT id FROM games WHERE platform_id = ?", platformId) { it.getInt(0) }.toSet()
    }

    fun deleteGames(ids: Set<Int>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            ids.forEach { c.execute("DELETE FROM games WHERE id = ?", it) }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun getSyncState(key: String): String? = withConn { c ->
        c.queryOne("SELECT value FROM sync_state WHERE key = ?", key) { it.getText(0) }
    }

    fun setSyncState(key: String, value: String) = withConn { c ->
        c.execute("INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)", key, value)
    }

    fun clearAll() = withConn { c ->
        c.execSQL("BEGIN")
        try {
            c.execSQL("DELETE FROM games")
            c.execSQL("DELETE FROM platforms")
            c.execSQL("DELETE FROM sync_state")
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
