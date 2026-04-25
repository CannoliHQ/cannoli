package dev.cannoli.scorza.db.importer

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.scanner.CollectionManager
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.OrderingManager
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.scanner.RecentlyPlayedManager
import dev.cannoli.scorza.util.ScanLog
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ImportResult {
    data object NotNeeded : ImportResult
    data class Success(val romCount: Int, val orphans: Int) : ImportResult
    data class Failure(val cause: Throwable) : ImportResult
}

fun interface ImportProgress {
    fun update(progress: Float, label: String)
}

class Importer(
    private val cannoliRoot: File,
    private val db: CannoliDatabase,
    private val platformResolver: PlatformResolver,
    private val scanner: FileScanner,
    private val collectionManager: CollectionManager,
    private val recentlyPlayedManager: RecentlyPlayedManager,
    private val orderingManager: OrderingManager,
    private val onProgress: ImportProgress,
) {
    private val conn: SQLiteConnection get() = db.conn
    private var orphans = 0

    fun run(): ImportResult {
        if (countRoms() > 0) return ImportResult.NotNeeded

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupDir = File(cannoliRoot, "Backup/import-$timestamp")

        ScanLog.startRun("database import")
        return try {
            conn.execSQL("BEGIN")
            try {
                announce(Phase.PLATFORMS)
                val tags = importPlatforms()

                announce(Phase.ROMS)
                val romIdsByPath = importRoms(tags)

                announce(Phase.FAVORITES)
                val favoritesId = ensureFavoritesCollection()

                announce(Phase.COLLECTIONS)
                val collectionIdsByStem = importCollections(favoritesId, romIdsByPath)

                announce(Phase.COLLECTION_PARENTS)
                importCollectionParents(collectionIdsByStem)

                announce(Phase.OVERRIDES)
                importGameOverrides(romIdsByPath)

                announce(Phase.RA_IDS)
                importRaGameIds(romIdsByPath)

                announce(Phase.RECENTLY_PLAYED)
                importRecentlyPlayed(romIdsByPath)

                announce(Phase.ORDERING)
                importOrdering(collectionIdsByStem)

                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
            }

            announce(Phase.ARCHIVE)
            archiveLegacyFiles(backupDir)

            announce(Phase.DONE)
            val result = ImportResult.Success(romIdsByPath().count(), orphans)
            ScanLog.write("import complete: roms=${result.romCount}, orphans=${result.orphans}")
            result
        } catch (t: Throwable) {
            ScanLog.write("ERROR import failed: ${t.message}")
            ImportResult.Failure(t)
        }
    }

    private enum class Phase(val start: Float, val end: Float, val label: String) {
        PLATFORMS(0.00f, 0.10f, "Cataloging platforms"),
        ROMS(0.10f, 0.55f, "Walking ROM directories"),
        FAVORITES(0.55f, 0.60f, "Creating Favorites collection"),
        COLLECTIONS(0.60f, 0.70f, "Migrating collections"),
        COLLECTION_PARENTS(0.70f, 0.78f, "Migrating collection hierarchy"),
        OVERRIDES(0.78f, 0.85f, "Migrating game overrides"),
        RA_IDS(0.85f, 0.90f, "Migrating RetroAchievements IDs"),
        RECENTLY_PLAYED(0.90f, 0.95f, "Migrating recently played"),
        ORDERING(0.95f, 0.97f, "Migrating ordering"),
        ARCHIVE(0.97f, 1.00f, "Archiving legacy files"),
        DONE(1.00f, 1.00f, "Done"),
    }

    private fun announce(phase: Phase) = onProgress.update(phase.start, phase.label)

    private fun progressWithin(phase: Phase, fraction: Float): Float =
        phase.start + (phase.end - phase.start) * fraction.coerceIn(0f, 1f)

    private fun importPlatforms(): List<String> {
        val tags = platformResolver.getAllTags()
        for (tag in tags) {
            conn.prepare("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)").use { stmt ->
                stmt.bindText(1, tag.uppercase())
                stmt.bindText(2, platformResolver.getDisplayName(tag))
                stmt.step()
            }
        }
        return tags.toList()
    }

    private fun importRoms(tags: List<String>): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        for ((index, tag) in tags.withIndex()) {
            val games = try {
                scanner.scanGames(tag)
            } catch (_: Throwable) { emptyList() }
            for (game in games) {
                val id = insertRom(
                    path = game.file.absolutePath,
                    platformTag = tag.uppercase(),
                    displayName = game.displayName,
                    artPath = game.artFile?.absolutePath,
                    isSubfolder = game.isSubfolder,
                    discPaths = game.discFiles?.map { it.absolutePath },
                )
                ids[game.file.absolutePath] = id
            }
            val fraction = (index + 1f) / tags.size.coerceAtLeast(1)
            onProgress.update(progressWithin(Phase.ROMS, fraction), "Cataloging ${tag.uppercase()}")
        }
        return ids
    }

    private fun insertRom(
        path: String,
        platformTag: String,
        displayName: String,
        artPath: String?,
        isSubfolder: Boolean,
        discPaths: List<String>?,
    ): Long {
        conn.prepare("""
            INSERT INTO roms (path, platform_tag, display_name, art_path, is_subfolder, disc_paths)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.bindText(1, path)
            stmt.bindText(2, platformTag)
            stmt.bindText(3, displayName)
            if (artPath != null) stmt.bindText(4, artPath) else stmt.bindNull(4)
            stmt.bindLong(5, if (isSubfolder) 1L else 0L)
            if (discPaths != null) stmt.bindText(6, JSONArray(discPaths).toString()) else stmt.bindNull(6)
            stmt.step()
        }
        return conn.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
    }

    private fun ensureFavoritesCollection(): Long {
        conn.prepare("""
            INSERT OR IGNORE INTO collections (display_name, collection_type)
            VALUES ('Favorites', 'FAVORITES')
        """.trimIndent()).use { it.step() }
        return conn.prepare(
            "SELECT id FROM collections WHERE collection_type = 'FAVORITES' LIMIT 1"
        ).use { it.step(); it.getLong(0) }
    }

    private fun importCollections(favoritesId: Long, romIdsByPath: Map<String, Long>): Map<String, Long> {
        val byStem = mutableMapOf<String, Long>()
        for (collection in collectionManager.scanCollections()) {
            val isFavorites = collection.stem.equals("Favorites", ignoreCase = true)
            val collectionId = if (isFavorites) {
                favoritesId
            } else {
                conn.prepare("INSERT INTO collections (display_name, collection_type) VALUES (?, 'STANDARD')").use { stmt ->
                    stmt.bindText(1, collection.displayName)
                    stmt.step()
                }
                conn.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
            }
            byStem[collection.stem] = collectionId

            val paths = collectionManager.getGamePaths(collection.stem)
            for (path in paths) {
                val romId = romIdsByPath[path]
                if (romId == null) {
                    orphan("collection ${collection.stem}", path)
                    continue
                }
                conn.prepare(
                    "INSERT OR IGNORE INTO collection_members (collection_id, rom_id) VALUES (?, ?)"
                ).use { stmt ->
                    stmt.bindLong(1, collectionId)
                    stmt.bindLong(2, romId)
                    stmt.step()
                }
            }
        }
        return byStem
    }

    private fun importCollectionParents(collectionIdsByStem: Map<String, Long>) {
        val parents = collectionManager.loadCollectionParents()
        for ((childStem, parentStem) in parents) {
            val childId = collectionIdsByStem[childStem]
            val parentId = collectionIdsByStem[parentStem]
            if (childId == null || parentId == null) {
                orphan("collection_parents", "$childStem -> $parentStem")
                continue
            }
            conn.prepare("UPDATE collections SET parent_id = ? WHERE id = ?").use { stmt ->
                stmt.bindLong(1, parentId)
                stmt.bindLong(2, childId)
                stmt.step()
            }
        }
    }

    private fun importGameOverrides(romIdsByPath: Map<String, Long>) {
        val overrides = platformResolver.snapshotGameOverrides()
        for ((path, override) in overrides) {
            val romId = romIdsByPath[path]
            if (romId == null) {
                orphan("game_override", path)
                continue
            }
            conn.prepare("""
                INSERT OR REPLACE INTO game_overrides (rom_id, core_id, runner, app_package, ra_package)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.bindLong(1, romId)
                if (override.coreId.isNotEmpty()) stmt.bindText(2, override.coreId) else stmt.bindNull(2)
                if (override.runner != null) stmt.bindText(3, override.runner) else stmt.bindNull(3)
                if (override.appPackage != null) stmt.bindText(4, override.appPackage) else stmt.bindNull(4)
                if (override.raPackage != null) stmt.bindText(5, override.raPackage) else stmt.bindNull(5)
                stmt.step()
            }
        }
    }

    private fun importRaGameIds(romIdsByPath: Map<String, Long>) {
        val file = File(cannoliRoot, "Config/RetroAchievements/ra_game_ids.txt")
        val legacy = File(cannoliRoot, "Config/RetroArch/ra_game_ids.txt")
        val source = if (file.exists()) file else if (legacy.exists()) legacy else return
        try {
            for (line in source.readLines()) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val path = line.substring(0, eq).trim()
                val gameId = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                val romId = romIdsByPath[path]
                if (romId == null) {
                    orphan("ra_game_id", path)
                    continue
                }
                conn.prepare("UPDATE roms SET ra_game_id = ? WHERE id = ?").use { stmt ->
                    stmt.bindLong(1, gameId.toLong())
                    stmt.bindLong(2, romId)
                    stmt.step()
                }
            }
        } catch (_: Throwable) { }
    }

    private fun importRecentlyPlayed(romIdsByPath: Map<String, Long>) {
        val paths = recentlyPlayedManager.load()
        if (paths.isEmpty()) return
        val now = System.currentTimeMillis()
        // Synthesize timestamps so MRU order from the legacy file is preserved.
        // Most recent gets `now`, next gets `now - 1ms`, and so on.
        for ((index, path) in paths.withIndex()) {
            val romId = romIdsByPath[path]
            if (romId == null) {
                orphan("recently_played", path)
                continue
            }
            conn.prepare("UPDATE roms SET last_played_at = ? WHERE id = ?").use { stmt ->
                stmt.bindLong(1, now - index)
                stmt.bindLong(2, romId)
                stmt.step()
            }
        }
    }

    private fun importOrdering(collectionIdsByStem: Map<String, Long>) {
        val platformOrder = orderingManager.loadPlatformOrder()
        for ((index, tag) in platformOrder.withIndex()) {
            conn.prepare("UPDATE platforms SET sort_order = ? WHERE tag = ?").use { stmt ->
                stmt.bindLong(1, index.toLong())
                stmt.bindText(2, tag.uppercase())
                stmt.step()
            }
        }
        val collectionOrder = orderingManager.loadCollectionOrder()
        for ((index, stem) in collectionOrder.withIndex()) {
            val id = collectionIdsByStem[stem] ?: continue
            conn.prepare("UPDATE collections SET sort_order = ? WHERE id = ?").use { stmt ->
                stmt.bindLong(1, index.toLong())
                stmt.bindLong(2, id)
                stmt.step()
            }
        }
    }

    private fun archiveLegacyFiles(backupDir: File) {
        backupDir.mkdirs()
        val candidates = listOf(
            File(cannoliRoot, "Collections"),
            File(cannoliRoot, "Config/cores.json"),
            File(cannoliRoot, "Config/RetroAchievements/ra_game_ids.txt"),
            File(cannoliRoot, "Config/RetroArch/ra_game_ids.txt"),
            File(cannoliRoot, "Config/State/recently_played.txt"),
            File(cannoliRoot, "Config/Ordering"),
        )
        for (src in candidates) {
            if (!src.exists()) continue
            val dest = File(backupDir, src.relativeTo(cannoliRoot).path)
            try {
                dest.parentFile?.mkdirs()
                if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                else src.copyTo(dest, overwrite = true)
                if (src.isDirectory) src.deleteRecursively() else src.delete()
            } catch (t: Throwable) {
                ScanLog.write("WARN failed to archive ${src.absolutePath}: ${t.message}")
            }
        }
    }

    private fun countRoms(): Int =
        conn.prepare("SELECT COUNT(*) FROM roms").use { it.step(); it.getInt(0) }

    private fun romIdsByPath(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        conn.prepare("SELECT id, path FROM roms").use { stmt ->
            while (stmt.step()) out[stmt.getText(1)] = stmt.getLong(0)
        }
        return out
    }

    private fun orphan(source: String, value: String) {
        orphans++
        ScanLog.write("orphan $source: $value")
    }
}
