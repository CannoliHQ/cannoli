package dev.cannoli.scorza.romm

import android.database.sqlite.SQLiteDatabase
import java.io.File

class RommCache(cacheDir: File) {
    private val db: SQLiteDatabase

    init {
        cacheDir.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(File(cacheDir, "romm_cache.db"), null)
        createTables()
    }

    private fun createTables() {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cache_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS platforms (
                id INTEGER PRIMARY KEY,
                slug TEXT NOT NULL,
                fs_slug TEXT NOT NULL,
                name TEXT NOT NULL,
                custom_name TEXT DEFAULT '',
                rom_count INTEGER DEFAULT 0,
                cached_at TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_platforms_fs_slug ON platforms(fs_slug)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS games (
                id INTEGER PRIMARY KEY,
                platform_id INTEGER NOT NULL,
                platform_fs_slug TEXT NOT NULL,
                name TEXT NOT NULL,
                fs_name TEXT DEFAULT '',
                fs_name_no_ext TEXT DEFAULT '',
                fs_size_bytes INTEGER DEFAULT 0,
                has_multiple_files INTEGER DEFAULT 0,
                updated_at TEXT DEFAULT '',
                cached_at TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_platform_id ON games(platform_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_platform_fs_slug ON games(platform_fs_slug)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_fs_lookup ON games(platform_fs_slug, fs_name_no_ext)")
    }

    fun sync(client: RommClient, log: (String) -> Unit) {
        val isBulkLoad = !hasCache()
        val updatedAfter = if (isBulkLoad) "" else getMetadata("games_refreshed_at")

        // Platforms: always refresh
        log("Fetching platforms...")
        val platforms = client.getPlatforms()
        log("Got ${platforms.size} platforms")
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM platforms")
            val now = now()
            for (p in platforms) {
                db.execSQL(
                    "INSERT INTO platforms (id, slug, fs_slug, name, custom_name, rom_count, cached_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(p.id, p.slug, p.fsSlug, p.name, p.customName, p.romCount, now)
                )
            }
            setMetadata("platforms_refreshed_at", now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Games: bulk or incremental
        if (isBulkLoad) {
            log("Fetching all games (bulk)...")
        } else {
            log("Fetching games updated since $updatedAfter...")
        }

        db.beginTransaction()
        try {
            var totalFetched = 0
            var offset = 0
            val limit = 1000

            while (true) {
                val query = if (updatedAfter.isNotEmpty()) {
                    GetRomsQuery(limit = limit, offset = offset, orderBy = "updated_at", orderDir = "asc")
                } else {
                    GetRomsQuery(limit = limit, offset = offset)
                }
                // Pass updated_after as a raw query param if incremental
                val page = if (updatedAfter.isNotEmpty()) {
                    client.getRomsUpdatedAfter(query, updatedAfter)
                } else {
                    client.getRoms(query)
                }

                val now = now()
                for (rom in page.items) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO games (id, platform_id, platform_fs_slug, name, fs_name, fs_name_no_ext, fs_size_bytes, has_multiple_files, updated_at, cached_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(rom.id, rom.platformId, rom.platformFsSlug, rom.name, rom.fsName, rom.fsNameNoExt, rom.fsSizeBytes, if (rom.hasMultipleFiles) 1 else 0, "", now)
                    )
                }
                totalFetched += page.items.size
                log("Cached $totalFetched / ${page.total} games...")
                offset += page.items.size
                if (offset >= page.total || page.items.isEmpty()) break
            }

            setMetadata("games_refreshed_at", now())
            db.setTransactionSuccessful()
            log("Synced $totalFetched games (${if (isBulkLoad) "bulk" else "incremental"})")
        } finally {
            db.endTransaction()
        }

        // Purge deleted games on incremental syncs
        if (!isBulkLoad) {
            log("Checking for deleted games...")
            try {
                val validIds = client.getRomIdentifiers()
                purgeDeletedGames(validIds, log)
            } catch (e: Exception) {
                log("Purge check failed: ${e.message}")
            }
        }
    }

    fun findRomId(fsSlug: String, nameNoExt: String): Int? {
        val cursor = db.rawQuery(
            "SELECT id FROM games WHERE platform_fs_slug = ? AND fs_name_no_ext = ? LIMIT 1",
            arrayOf(fsSlug, nameNoExt)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    fun getPlatforms(): List<CachedPlatform> {
        val cursor = db.rawQuery("SELECT id, fs_slug, name, custom_name, rom_count FROM platforms ORDER BY name", null)
        val result = mutableListOf<CachedPlatform>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(CachedPlatform(
                    id = it.getInt(0),
                    fsSlug = it.getString(1),
                    name = it.getString(2),
                    customName = it.getString(3) ?: "",
                    romCount = it.getInt(4)
                ))
            }
        }
        return result
    }

    fun hasCache(): Boolean {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM games", null)
        return cursor.use { it.moveToFirst() && it.getInt(0) > 0 }
    }

    fun gameCount(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM games", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun purgeDeletedGames(validIds: List<Int>, log: (String) -> Unit): Int {
        if (validIds.isEmpty()) return 0
        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS _valid_ids (id INTEGER PRIMARY KEY)")
            db.execSQL("DELETE FROM _valid_ids")
            val batch = 400
            for (i in validIds.indices step batch) {
                val end = minOf(i + batch, validIds.size)
                val placeholders = (i until end).joinToString(",") { "(?)" }
                val args = validIds.subList(i, end).map { it as Any }.toTypedArray()
                db.execSQL("INSERT OR IGNORE INTO _valid_ids (id) VALUES $placeholders", args)
            }
            val cursor = db.rawQuery("SELECT COUNT(*) FROM games WHERE id NOT IN (SELECT id FROM _valid_ids)", null)
            val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (count > 0) {
                db.execSQL("DELETE FROM games WHERE id NOT IN (SELECT id FROM _valid_ids)")
                log("Purged $count deleted games from cache")
            }
            db.execSQL("DROP TABLE IF EXISTS _valid_ids")
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun close() { db.close() }

    private fun setMetadata(key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO cache_metadata (key, value, updated_at) VALUES (?, ?, ?)",
            arrayOf(key, value, now())
        )
    }

    private fun getMetadata(key: String): String {
        val cursor = db.rawQuery("SELECT value FROM cache_metadata WHERE key = ?", arrayOf(key))
        return cursor.use { if (it.moveToFirst()) it.getString(0) else "" }
    }

    private fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())

    data class CachedPlatform(val id: Int, val fsSlug: String, val name: String, val customName: String, val romCount: Int) {
        val displayName: String get() = customName.ifEmpty { name }
    }
}
