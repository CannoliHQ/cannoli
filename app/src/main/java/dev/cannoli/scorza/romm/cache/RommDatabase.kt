package dev.cannoli.scorza.romm.cache

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.TextNormalizer
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
            c.execSQL("DROP TABLE IF EXISTS collections")
            c.execSQL("DROP TABLE IF EXISTS collection_roms")
        }
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS platforms (
                id INTEGER PRIMARY KEY,
                slug TEXT NOT NULL,
                cannoli_tag TEXT NOT NULL,
                display_name TEXT NOT NULL,
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT
            )
        """.trimIndent())
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS games (
                id INTEGER PRIMARY KEY,
                platform_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                name_normalized TEXT NOT NULL DEFAULT '',
                fs_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                summary TEXT,
                revision TEXT,
                regions TEXT NOT NULL DEFAULT '[]',
                languages TEXT NOT NULL DEFAULT '[]',
                companies TEXT NOT NULL DEFAULT '[]',
                genres TEXT NOT NULL DEFAULT '[]',
                game_modes TEXT NOT NULL DEFAULT '[]',
                first_release_date INTEGER,
                cover_path TEXT,
                files_json TEXT NOT NULL DEFAULT '[]',
                ss_media_json TEXT NOT NULL DEFAULT '{}',
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT
            )
        """.trimIndent())
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_games_platform_sort ON games(platform_id, sort_key)")
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_games_name_normalized ON games(name_normalized)")
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """.trimIndent())
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS collections (
                id TEXT PRIMARY KEY,
                coll_group TEXT NOT NULL,
                name TEXT NOT NULL,
                rom_count INTEGER NOT NULL DEFAULT 0,
                sort_key TEXT NOT NULL DEFAULT '',
                updated_at TEXT
            )
        """.trimIndent())
        c.execSQL("""
            CREATE TABLE IF NOT EXISTS collection_roms (
                collection_id TEXT NOT NULL,
                rom_id INTEGER NOT NULL,
                PRIMARY KEY (collection_id, rom_id)
            )
        """.trimIndent())
        c.execSQL("CREATE INDEX IF NOT EXISTS idx_collection_roms_collection ON collection_roms(collection_id)")
        c.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
    }

    fun platforms(): List<RommPlatform> = withConn { c ->
        c.queryAll(
            """
            SELECT p.id, p.slug, p.cannoli_tag, p.display_name, COUNT(g.id)
            FROM platforms p LEFT JOIN games g ON g.platform_id = p.id
            GROUP BY p.id
            HAVING COUNT(g.id) > 0
            ORDER BY p.sort_key
            """.trimIndent()
        ) {
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
        "INSERT OR REPLACE INTO platforms (id, slug, cannoli_tag, display_name, sort_key, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        p.id, p.slug, p.cannoliTag, p.displayName, NaturalSort.toSortKey(p.displayName), updatedAt,
    )

    fun upsertGames(records: List<GameRecord>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            records.forEach { rec ->
                val g = rec.game
                c.execute(
                    """INSERT OR REPLACE INTO games
                       (id, platform_id, name, name_normalized, fs_name, size_bytes, summary, revision, regions, languages, companies, genres, game_modes, first_release_date, cover_path, files_json, ss_media_json, sort_key, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    g.id, g.platformId, g.name, TextNormalizer.normalize(g.name), g.fsName, g.sizeBytes, g.summary, g.revision,
                    RommCacheJson.encodeStrings(g.regions), RommCacheJson.encodeStrings(g.languages),
                    RommCacheJson.encodeStrings(g.companies), RommCacheJson.encodeStrings(g.genres),
                    RommCacheJson.encodeStrings(g.gameModes), g.firstReleaseDate,
                    g.coverPath, RommCacheJson.encodeFiles(g.files), RommCacheJson.encodeSsMedia(g.ssMedia),
                    NaturalSort.toSortKey(g.name), rec.updatedAt,
                )
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    private fun rowToGame(stmt: androidx.sqlite.SQLiteStatement) = RommGame(
        id = stmt.getInt(0),
        platformId = stmt.getInt(1),
        name = stmt.getText(2),
        fsName = stmt.getText(3),
        sizeBytes = stmt.getLong(4),
        summary = if (stmt.isNull(5)) null else stmt.getText(5),
        revision = if (stmt.isNull(6)) null else stmt.getText(6),
        regions = RommCacheJson.decodeStrings(stmt.getText(7)),
        languages = RommCacheJson.decodeStrings(stmt.getText(8)),
        companies = RommCacheJson.decodeStrings(stmt.getText(9)),
        genres = RommCacheJson.decodeStrings(stmt.getText(10)),
        gameModes = RommCacheJson.decodeStrings(stmt.getText(11)),
        firstReleaseDate = if (stmt.isNull(12)) null else stmt.getLong(12),
        coverPath = if (stmt.isNull(13)) null else stmt.getText(13),
        files = RommCacheJson.decodeFiles(stmt.getText(14)),
        ssMedia = RommCacheJson.decodeSsMedia(stmt.getText(15)),
    )

    fun games(platformId: Int, search: String?, limit: Int, offset: Int): List<RommGame> = withConn { c ->
        val like = search?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val sql = buildString {
            append("SELECT id, platform_id, name, fs_name, size_bytes, summary, revision, regions, languages, companies, genres, game_modes, first_release_date, cover_path, files_json, ss_media_json FROM games WHERE platform_id = ?")
            if (like != null) append(" AND name_normalized LIKE ?")
            append(" ORDER BY sort_key LIMIT ? OFFSET ?")
        }
        val args: Array<Any?> = if (like != null) arrayOf(platformId, like, limit, offset) else arrayOf(platformId, limit, offset)
        c.queryAll(sql, *args, mapper = ::rowToGame)
    }

    fun searchAllGames(query: RommSearchQuery): List<RommGame> = withConn { c ->
        val term = TextNormalizer.normalize(query.text)
        if (term.isEmpty()) return@withConn emptyList()
        c.queryAll(
            "SELECT id, platform_id, name, fs_name, size_bytes, summary, revision, regions, languages, companies, genres, game_modes, first_release_date, cover_path, files_json, ss_media_json FROM games WHERE name_normalized LIKE ? ORDER BY sort_key LIMIT ?",
            "%$term%",
            GLOBAL_SEARCH_LIMIT,
            mapper = ::rowToGame,
        )
    }

    fun allGames(platformId: Int): List<RommGame> = withConn { c ->
        c.queryAll(
            "SELECT id, platform_id, name, fs_name, size_bytes, summary, revision, regions, languages, companies, genres, game_modes, first_release_date, cover_path, files_json, ss_media_json FROM games WHERE platform_id = ?",
            platformId,
            mapper = ::rowToGame,
        )
    }

    fun gamesCount(platformId: Int, search: String?): Int = withConn { c ->
        val like = search?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val sql = if (like != null) "SELECT COUNT(*) FROM games WHERE platform_id = ? AND name_normalized LIKE ?"
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

    fun allGameIds(): Set<Int> = withConn { c ->
        c.queryAll("SELECT id FROM games") { it.getInt(0) }.toSet()
    }

    fun deleteGames(ids: Set<Int>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            ids.forEach { c.execute("DELETE FROM games WHERE id = ?", it) }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun allPlatformIds(): Set<Int> = withConn { c ->
        c.queryAll("SELECT id FROM platforms") { it.getInt(0) }.toSet()
    }

    fun deletePlatforms(ids: Set<Int>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            ids.forEach {
                c.execute("DELETE FROM games WHERE platform_id = ?", it)
                c.execute("DELETE FROM platforms WHERE id = ?", it)
            }
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
            c.execSQL("DELETE FROM collections")
            c.execSQL("DELETE FROM collection_roms")
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun upsertCollections(rows: List<Pair<RommCollection, String?>>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            for ((coll, updatedAt) in rows) {
                c.execute(
                    "INSERT OR REPLACE INTO collections (id, coll_group, name, rom_count, sort_key, updated_at) VALUES (?,?,?,?,?,?)",
                    coll.id, coll.group.name, coll.name, coll.romCount, TextNormalizer.normalize(coll.name), updatedAt,
                )
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun setCollectionMembers(collectionId: String, romIds: List<Int>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            c.execute("DELETE FROM collection_roms WHERE collection_id = ?", collectionId)
            for (rid in romIds) {
                c.execute("INSERT OR IGNORE INTO collection_roms (collection_id, rom_id) VALUES (?,?)", collectionId, rid)
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    fun collections(groups: Set<RommCollectionGroup>): List<RommCollection> = withConn { c ->
        if (groups.isEmpty()) return@withConn emptyList()
        val placeholders = groups.joinToString(",") { "?" }
        val args: Array<Any?> = groups.map { it.name }.toTypedArray()
        c.queryAll(
            """SELECT c.id, c.coll_group, c.name, c.rom_count FROM collections c
               WHERE c.coll_group IN ($placeholders)
                 AND EXISTS (SELECT 1 FROM collection_roms cr JOIN games g ON g.id = cr.rom_id WHERE cr.collection_id = c.id)
               ORDER BY c.sort_key""",
            *args,
            mapper = { stmt ->
                RommCollection(stmt.getText(0), RommCollectionGroup.valueOf(stmt.getText(1)), stmt.getText(2), stmt.getInt(3))
            },
        )
    }

    fun gamesForCollection(collectionId: String, search: String?, limit: Int, offset: Int): List<RommGame> = withConn { c ->
        val like = search?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val sql = buildString {
            append("SELECT g.id, g.platform_id, g.name, g.fs_name, g.size_bytes, g.summary, g.revision, g.regions, g.languages, g.companies, g.genres, g.game_modes, g.first_release_date, g.cover_path, g.files_json, g.ss_media_json ")
            append("FROM games g JOIN collection_roms cr ON cr.rom_id = g.id WHERE cr.collection_id = ?")
            if (like != null) append(" AND g.name_normalized LIKE ?")
            append(" ORDER BY g.sort_key LIMIT ? OFFSET ?")
        }
        val args: Array<Any?> = if (like != null) arrayOf(collectionId, like, limit, offset) else arrayOf(collectionId, limit, offset)
        c.queryAll(sql, *args, mapper = ::rowToGame)
    }

    fun gamesForCollectionCount(collectionId: String, search: String?): Int = withConn { c ->
        val like = search?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val sql = if (like != null)
            "SELECT COUNT(*) FROM collection_roms cr JOIN games g ON g.id = cr.rom_id WHERE cr.collection_id = ? AND g.name_normalized LIKE ?"
        else
            "SELECT COUNT(*) FROM collection_roms cr JOIN games g ON g.id = cr.rom_id WHERE cr.collection_id = ?"
        val args: Array<Any?> = if (like != null) arrayOf(collectionId, like) else arrayOf(collectionId)
        c.queryOne(sql, *args) { it.getInt(0) } ?: 0
    }

    fun allCollectionIds(): Set<String> = withConn { c ->
        c.queryAll("SELECT id FROM collections", mapper = { it.getText(0) }).toSet()
    }

    fun deleteCollections(ids: Set<String>) = withConn { c ->
        c.execSQL("BEGIN")
        try {
            ids.forEach { id ->
                c.execute("DELETE FROM collections WHERE id = ?", id)
                c.execute("DELETE FROM collection_roms WHERE collection_id = ?", id)
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) { c.execSQL("ROLLBACK"); throw t }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val GLOBAL_SEARCH_LIMIT = 300
    }
}
