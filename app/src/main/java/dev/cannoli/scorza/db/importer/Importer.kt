package dev.cannoli.scorza.db.importer

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.executeReturningId
import dev.cannoli.scorza.db.query
import dev.cannoli.scorza.db.transaction
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.util.ScanLog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ImportResult {
    data object NotNeeded : ImportResult
    data class Success(val romCount: Int, val appCount: Int, val orphans: Int) : ImportResult
    data class Failure(val cause: Throwable) : ImportResult
}

fun interface ImportProgress {
    fun update(progress: Float, label: String)
}

class Importer(
    private val cannoliRoot: File,
    private val romDirectory: File,
    private val db: CannoliDatabase,
    private val platformConfig: PlatformConfig,
    private val romScanner: RomScanner,
    private val onProgress: ImportProgress,
) {
    private val paths = CannoliPaths(cannoliRoot)
    private val collectionsDir = paths.collectionsDir
    private val collectionParentsFile = File(collectionsDir, "collection_parents.txt")
    private val orderingDir = paths.configOrdering
    private val recentlyPlayedFile = paths.recentlyPlayedFile
    private val toolsDir = paths.toolsDir
    private val portsDir = paths.portsDir

    private val conn: SQLiteConnection get() = db.conn
    private var orphans = 0

    fun run(): ImportResult = db.withConn { _ ->
        if (countRoms() > 0 || countApps() > 0) return@withConn ImportResult.NotNeeded

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupDir = File(paths.backupDir, "import-$timestamp")

        ScanLog.startRun("database import")
        val toolNames = mutableMapOf<String, Long>()
        val portNames = mutableMapOf<String, Long>()
        val romIdsByRelative = mutableMapOf<String, Long>()

        try {
            announce(Phase.PLATFORMS)
            importPlatforms()

            announce(Phase.ROMS)
            importRoms(romIdsByRelative)

            conn.transaction {
                announce(Phase.APPS)
                importApps(toolNames, portNames)

                announce(Phase.FAVORITES)
                val favoritesId = ensureFavoritesCollection()

                announce(Phase.COLLECTIONS)
                val collectionIdsByStem = importCollections(favoritesId, romIdsByRelative, toolNames, portNames)

                announce(Phase.COLLECTION_PARENTS)
                importCollectionParents(collectionIdsByStem)

                announce(Phase.OVERRIDES)
                importGameOverrides(romIdsByRelative)

                announce(Phase.RA_IDS)
                importRaGameIds(romIdsByRelative)

                announce(Phase.RECENTLY_PLAYED)
                importRecentlyPlayed(romIdsByRelative, toolNames, portNames)

                announce(Phase.ORDERING)
                importOrdering(collectionIdsByStem)
            }
            conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")

            announce(Phase.ARCHIVE)
            archiveLegacyFiles(backupDir)

            announce(Phase.DONE)
            val result = ImportResult.Success(romIdsByRelative.size, toolNames.size + portNames.size, orphans)
            ScanLog.write("import complete: roms=${result.romCount}, apps=${result.appCount}, orphans=${result.orphans}")
            result
        } catch (t: Throwable) {
            ScanLog.write("ERROR import failed: ${t.message}")
            ImportResult.Failure(t)
        }
    }

    private enum class Phase(val start: Float, val end: Float, val label: String) {
        PLATFORMS(0.00f, 0.10f, "Cataloging platforms"),
        ROMS(0.10f, 0.55f, "Walking ROM directories"),
        APPS(0.55f, 0.60f, "Cataloging apps"),
        FAVORITES(0.60f, 0.62f, "Creating Favorites collection"),
        COLLECTIONS(0.62f, 0.72f, "Migrating collections"),
        COLLECTION_PARENTS(0.72f, 0.78f, "Migrating collection hierarchy"),
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

    private fun importPlatforms() {
        for (tag in platformConfig.getAllTags()) {
            upsertPlatform(tag.uppercase(), platformConfig.getDisplayName(tag))
        }
    }

    private fun upsertPlatform(tag: String, displayName: String) = conn.execute(
        """
        INSERT INTO platforms (tag, display_name) VALUES (?, ?)
        ON CONFLICT(tag) DO UPDATE SET display_name = excluded.display_name
        """.trimIndent(),
        tag, displayName,
    )

    private fun ensurePlatformRow(tag: String) = conn.execute(
        "INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)",
        tag, tag,
    )

    private fun importRoms(romIdsByRelative: MutableMap<String, Long>) {
        val tags = platformConfig.getAllTags()
        for ((index, tag) in tags.withIndex()) {
            val upperTag = tag.uppercase()
            try {
                romScanner.scanPlatform(upperTag, isArcade = platformConfig.isArcade(upperTag))
            } catch (t: Throwable) {
                ScanLog.write("WARN scanPlatform $upperTag failed during import: ${t.message}")
            }
            conn.query("SELECT id, path FROM roms WHERE platform_tag = ?") { stmt ->
                stmt.bindText(1, upperTag)
                while (stmt.step()) {
                    romIdsByRelative[stmt.getText(1)] = stmt.getLong(0)
                }
            }
            val fraction = (index + 1f) / tags.size.coerceAtLeast(1)
            onProgress.update(progressWithin(Phase.ROMS, fraction), "Cataloging $upperTag")
        }
    }

    private fun importApps(toolNames: MutableMap<String, Long>, portNames: MutableMap<String, Long>) {
        for ((displayName, packageName) in scanLegacyApkLaunches(toolsDir)) {
            val id = insertApp("TOOL", displayName, packageName) ?: continue
            toolNames[displayName] = id
        }
        for ((displayName, packageName) in scanLegacyApkLaunches(portsDir)) {
            val id = insertApp("PORT", displayName, packageName) ?: continue
            portNames[displayName] = id
        }
    }

    private fun insertApp(type: String, displayName: String, packageName: String): Long? {
        conn.execute(
            "INSERT OR IGNORE INTO apps (type, display_name, package_name) VALUES (?, ?, ?)",
            type, displayName, packageName,
        )
        return conn.query("SELECT id FROM apps WHERE type = ? AND package_name = ?") { stmt ->
            stmt.bindText(1, type)
            stmt.bindText(2, packageName)
            if (stmt.step()) stmt.getLong(0) else null
        }
    }

    private fun ensureFavoritesCollection(): Long {
        conn.execute("INSERT OR IGNORE INTO collections (display_name, collection_type) VALUES ('Favorites', 'FAVORITES')")
        return conn.query("SELECT id FROM collections WHERE collection_type = 'FAVORITES' LIMIT 1") {
            it.step(); it.getLong(0)
        }
    }

    private fun importCollections(
        favoritesId: Long,
        romIdsByRelative: Map<String, Long>,
        toolNames: Map<String, Long>,
        portNames: Map<String, Long>,
    ): Map<String, Long> {
        val byStem = mutableMapOf<String, Long>()
        for ((stem, displayName) in scanLegacyCollections()) {
            val isFavorites = stem.equals("Favorites", ignoreCase = true)
            val collectionId = if (isFavorites) favoritesId
            else conn.executeReturningId(
                "INSERT INTO collections (display_name, collection_type) VALUES (?, 'STANDARD')",
                displayName,
            )
            byStem[stem] = collectionId

            for (path in readLegacyCollectionMembers(stem)) {
                when (val ref = resolveLegacyPath(path, romIdsByRelative, toolNames, portNames)) {
                    is LibraryRef.Rom -> insertRomMember(collectionId, ref.id)
                    is LibraryRef.App -> insertAppMember(collectionId, ref.id)
                    null -> orphan("collection $stem", path)
                }
            }
        }
        return byStem
    }

    private fun insertRomMember(collectionId: Long, romId: Long) = conn.execute(
        "INSERT OR IGNORE INTO collection_members (collection_id, rom_id) VALUES (?, ?)",
        collectionId, romId,
    )

    private fun insertAppMember(collectionId: Long, appId: Long) = conn.execute(
        "INSERT OR IGNORE INTO collection_members (collection_id, app_id) VALUES (?, ?)",
        collectionId, appId,
    )

    private fun importCollectionParents(collectionIdsByStem: Map<String, Long>) {
        for ((childStem, parentStem) in readLegacyCollectionParents()) {
            val childId = collectionIdsByStem[childStem]
            val parentId = collectionIdsByStem[parentStem]
            if (childId == null || parentId == null) {
                orphan("collection_parents", "$childStem -> $parentStem")
                continue
            }
            conn.execute("UPDATE collections SET parent_id = ? WHERE id = ?", parentId, childId)
        }
    }

    private fun importGameOverrides(romIdsByRelative: Map<String, Long>) {
        for ((absolutePath, override) in platformConfig.snapshotGameOverrides()) {
            val romId = relativizeRom(File(absolutePath))?.let { romIdsByRelative[it] }
            if (romId == null) {
                orphan("game_override", absolutePath)
                continue
            }
            conn.execute(
                """
                INSERT OR REPLACE INTO game_overrides (rom_id, core_id, runner, app_package, ra_package)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                romId,
                override.coreId.takeIf { it.isNotEmpty() },
                override.runner,
                override.appPackage,
                override.raPackage,
            )
        }
    }

    private fun importRaGameIds(romIdsByRelative: Map<String, Long>) {
        val file = paths.raGameIdsFile
        val legacy = paths.raGameIdsLegacyFile
        val source = if (file.exists()) file else if (legacy.exists()) legacy else return
        try {
            for (line in source.readLines()) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val absolutePath = line.substring(0, eq).trim()
                val gameId = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                val romId = relativizeRom(File(absolutePath))?.let { romIdsByRelative[it] }
                if (romId == null) {
                    orphan("ra_game_id", absolutePath)
                    continue
                }
                conn.execute("UPDATE roms SET ra_game_id = ? WHERE id = ?", gameId, romId)
            }
        } catch (t: Throwable) {
            ScanLog.write("WARN ra_game_ids import failed: ${t.message}")
        }
    }

    private fun importRecentlyPlayed(
        romIdsByRelative: Map<String, Long>,
        toolNames: Map<String, Long>,
        portNames: Map<String, Long>,
    ) {
        val paths = readLegacyRecentlyPlayed()
        if (paths.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((index, path) in paths.withIndex()) {
            val timestamp = now - index
            when (val ref = resolveLegacyPath(path, romIdsByRelative, toolNames, portNames)) {
                is LibraryRef.Rom -> conn.execute("UPDATE roms SET last_played_at = ? WHERE id = ?", timestamp, ref.id)
                is LibraryRef.App -> conn.execute("UPDATE apps SET last_played_at = ? WHERE id = ?", timestamp, ref.id)
                null -> orphan("recently_played", path)
            }
        }
    }

    private fun importOrdering(collectionIdsByStem: Map<String, Long>) {
        for ((index, rawTag) in readLegacyOrderingFile("platform_order.txt").withIndex()) {
            val tag = rawTag.uppercase()
            ensurePlatformRow(tag)
            conn.execute("UPDATE platforms SET sort_order = ? WHERE tag = ?", index.toLong(), tag)
        }
        for ((index, stem) in readLegacyOrderingFile("collection_order.txt").withIndex()) {
            val id = collectionIdsByStem[stem] ?: continue
            conn.execute("UPDATE collections SET sort_order = ? WHERE id = ?", index.toLong(), id)
        }
    }

    private fun resolveLegacyPath(
        absolutePath: String,
        romIdsByRelative: Map<String, Long>,
        toolNames: Map<String, Long>,
        portNames: Map<String, Long>,
    ): LibraryRef? {
        val relative = relativizeRom(File(absolutePath))
        if (relative != null) {
            romIdsByRelative[relative]?.let { return LibraryRef.Rom(it) }
        }
        val toolsRoot = toolsDir.absolutePath + File.separator
        val portsRoot = portsDir.absolutePath + File.separator
        if (absolutePath.startsWith(toolsRoot)) {
            toolNames[File(absolutePath).nameWithoutExtension]?.let { return LibraryRef.App(it) }
        }
        if (absolutePath.startsWith(portsRoot)) {
            portNames[File(absolutePath).nameWithoutExtension]?.let { return LibraryRef.App(it) }
        }
        return null
    }

    private fun relativizeRom(absolute: File): String? {
        return try {
            val relative = absolute.relativeTo(romDirectory).path
            if (relative.startsWith("..")) null else relative
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun scanLegacyCollections(): List<Pair<String, String>> {
        if (!collectionsDir.exists()) return emptyList()
        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return emptyList()
        return files.map { it.nameWithoutExtension to Collection.stemToDisplayName(it.nameWithoutExtension) }
    }

    private fun readLegacyCollectionMembers(stem: String): List<String> {
        val file = File(collectionsDir, "$stem.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: IOException) { emptyList() }
    }

    private fun readLegacyCollectionParents(): Map<String, String> {
        if (!collectionParentsFile.exists()) return emptyMap()
        return try {
            val map = linkedMapOf<String, String>()
            for (line in collectionParentsFile.readLines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || '=' !in trimmed) continue
                val (child, parent) = trimmed.split('=', limit = 2)
                val c = child.trim(); val p = parent.trim()
                if (c.isNotEmpty() && p.isNotEmpty()) map[c] = p
            }
            map
        } catch (_: IOException) { emptyMap() }
    }

    private fun readLegacyOrderingFile(name: String): List<String> {
        val file = File(orderingDir, name)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: IOException) { emptyList() }
    }

    private fun readLegacyRecentlyPlayed(): List<String> {
        if (!recentlyPlayedFile.exists()) return emptyList()
        return try {
            recentlyPlayedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        } catch (_: IOException) { emptyList() }
    }

    private fun scanLegacyApkLaunches(dir: File): List<Pair<String, String>> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()
        return files.mapNotNull { file ->
            val pkg = try { file.readText().trim() } catch (_: IOException) { return@mapNotNull null }
            if (pkg.isEmpty()) null else file.nameWithoutExtension to pkg
        }
    }

    private fun archiveLegacyFiles(backupDir: File) {
        backupDir.mkdirs()
        val candidates = listOf(
            paths.collectionsDir,
            paths.coresJson,
            paths.raGameIdsFile,
            paths.raGameIdsLegacyFile,
            paths.recentlyPlayedFile,
            paths.configOrdering,
            paths.toolsDir,
            paths.portsDir,
            paths.platformCacheFile,
            paths.gameCacheFile,
        )
        for (src in candidates) {
            if (!src.exists()) continue
            val dest = File(backupDir, src.relativeTo(cannoliRoot).path)
            try {
                dest.parentFile?.mkdirs()
                if (!src.renameTo(dest)) {
                    if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                    else src.copyTo(dest, overwrite = true)
                    if (src.isDirectory) src.deleteRecursively() else src.delete()
                }
                pruneEmptyParents(src)
            } catch (t: Throwable) {
                ScanLog.write("WARN failed to archive ${src.absolutePath}: ${t.message}")
            }
        }
    }

    private fun pruneEmptyParents(start: File) {
        var dir = start.parentFile ?: return
        while (dir.absolutePath != cannoliRoot.absolutePath && dir.startsWith(cannoliRoot)) {
            val children = dir.listFiles() ?: break
            if (children.isNotEmpty()) break
            if (!dir.delete()) break
            dir = dir.parentFile ?: break
        }
    }

    private fun countRoms(): Int =
        conn.query("SELECT COUNT(*) FROM roms") { it.step(); it.getInt(0) }

    private fun countApps(): Int =
        conn.query("SELECT COUNT(*) FROM apps") { it.step(); it.getInt(0) }

    private fun orphan(source: String, value: String) {
        orphans++
        ScanLog.write("orphan $source: $value")
    }
}
