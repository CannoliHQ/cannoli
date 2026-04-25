package dev.cannoli.scorza.db.importer

import android.content.res.AssetManager
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.library.ArtworkLookup
import dev.cannoli.scorza.library.NameMapLookup
import dev.cannoli.scorza.library.RomScanner
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.scanner.PlatformResolver
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
    private val platformResolver: PlatformResolver,
    private val assets: AssetManager,
    private val onProgress: ImportProgress,
) {
    private val collectionsDir = File(cannoliRoot, "Collections")
    private val collectionParentsFile = File(collectionsDir, "collection_parents.txt")
    private val orderingDir = File(cannoliRoot, "Config/Ordering")
    private val recentlyPlayedFile = File(cannoliRoot, "Config/State/recently_played.txt")
    private val toolsDir = File(cannoliRoot, "Config/Launch Scripts/Tools")
    private val portsDir = File(cannoliRoot, "Config/Launch Scripts/Ports")

    private val conn: SQLiteConnection get() = db.conn
    private var orphans = 0

    private sealed interface MemberRef {
        data class Rom(val id: Long) : MemberRef
        data class App(val id: Long) : MemberRef
    }

    fun run(): ImportResult {
        if (countRoms() > 0 || countApps() > 0) return ImportResult.NotNeeded
        migrateLegacyCollectionsToHashedNames()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupDir = File(cannoliRoot, "Backup/import-$timestamp")

        ScanLog.startRun("database import")
        val toolNames = mutableMapOf<String, Long>()
        val portNames = mutableMapOf<String, Long>()
        val romIdsByRelative = mutableMapOf<String, Long>()

        return try {
            announce(Phase.PLATFORMS)
            importPlatforms()

            announce(Phase.ROMS)
            importRoms(romIdsByRelative)

            conn.execSQL("BEGIN")
            try {
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

                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                conn.execSQL("ROLLBACK")
                throw t
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
        val tags = platformResolver.getAllTags()
        for (tag in tags) {
            conn.prepare("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)").use { stmt ->
                stmt.bindText(1, tag.uppercase())
                stmt.bindText(2, platformResolver.getDisplayName(tag))
                stmt.step()
            }
        }
    }

    private fun importRoms(romIdsByRelative: MutableMap<String, Long>) {
        val tags = platformResolver.getAllTags()
        val artworkLookup = ArtworkLookup(cannoliRoot)
        val nameMapLookup = NameMapLookup(cannoliRoot)
        val romScanner = RomScanner(cannoliRoot, romDirectory, db, nameMapLookup, artworkLookup).also {
            it.loadIgnoreLists(assets)
        }
        for ((index, tag) in tags.withIndex()) {
            val upperTag = tag.uppercase()
            try {
                romScanner.scanPlatform(upperTag, isArcade = platformResolver.isArcade(upperTag))
            } catch (t: Throwable) {
                ScanLog.write("WARN scanPlatform $upperTag failed during import: ${t.message}")
            }
            conn.prepare("SELECT id, path FROM roms WHERE platform_tag = ?").use { stmt ->
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
        conn.prepare("""
            INSERT OR IGNORE INTO apps (type, display_name, package_name) VALUES (?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.bindText(1, type)
            stmt.bindText(2, displayName)
            stmt.bindText(3, packageName)
            stmt.step()
        }
        return conn.prepare("SELECT id FROM apps WHERE type = ? AND package_name = ?").use { stmt ->
            stmt.bindText(1, type)
            stmt.bindText(2, packageName)
            if (stmt.step()) stmt.getLong(0) else null
        }
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

    private fun importCollections(
        favoritesId: Long,
        romIdsByRelative: Map<String, Long>,
        toolNames: Map<String, Long>,
        portNames: Map<String, Long>,
    ): Map<String, Long> {
        val byStem = mutableMapOf<String, Long>()
        for ((stem, displayName) in scanLegacyCollections()) {
            val isFavorites = stem.equals("Favorites", ignoreCase = true)
            val collectionId = if (isFavorites) {
                favoritesId
            } else {
                conn.prepare("INSERT INTO collections (display_name, collection_type) VALUES (?, 'STANDARD')").use { stmt ->
                    stmt.bindText(1, displayName)
                    stmt.step()
                }
                conn.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
            }
            byStem[stem] = collectionId

            for (path in readLegacyCollectionMembers(stem)) {
                when (val ref = resolveLegacyPath(path, romIdsByRelative, toolNames, portNames)) {
                    is MemberRef.Rom -> insertRomMember(collectionId, ref.id)
                    is MemberRef.App -> insertAppMember(collectionId, ref.id)
                    null -> orphan("collection $stem", path)
                }
            }
        }
        return byStem
    }

    private fun insertRomMember(collectionId: Long, romId: Long) {
        conn.prepare(
            "INSERT OR IGNORE INTO collection_members (collection_id, rom_id) VALUES (?, ?)"
        ).use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.bindLong(2, romId)
            stmt.step()
        }
    }

    private fun insertAppMember(collectionId: Long, appId: Long) {
        conn.prepare(
            "INSERT OR IGNORE INTO collection_members (collection_id, app_id) VALUES (?, ?)"
        ).use { stmt ->
            stmt.bindLong(1, collectionId)
            stmt.bindLong(2, appId)
            stmt.step()
        }
    }

    private fun importCollectionParents(collectionIdsByStem: Map<String, Long>) {
        for ((childStem, parentStem) in readLegacyCollectionParents()) {
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

    private fun importGameOverrides(romIdsByRelative: Map<String, Long>) {
        val overrides = platformResolver.snapshotGameOverrides()
        for ((absolutePath, override) in overrides) {
            val relative = relativizeRom(File(absolutePath))
            val romId = relative?.let { romIdsByRelative[it] }
            if (romId == null) {
                orphan("game_override", absolutePath)
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

    private fun importRaGameIds(romIdsByRelative: Map<String, Long>) {
        val file = File(cannoliRoot, "Config/RetroAchievements/ra_game_ids.txt")
        val legacy = File(cannoliRoot, "Config/RetroArch/ra_game_ids.txt")
        val source = if (file.exists()) file else if (legacy.exists()) legacy else return
        try {
            for (line in source.readLines()) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val absolutePath = line.substring(0, eq).trim()
                val gameId = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                val relative = relativizeRom(File(absolutePath))
                val romId = relative?.let { romIdsByRelative[it] }
                if (romId == null) {
                    orphan("ra_game_id", absolutePath)
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
                is MemberRef.Rom -> conn.prepare("UPDATE roms SET last_played_at = ? WHERE id = ?").use { stmt ->
                    stmt.bindLong(1, timestamp)
                    stmt.bindLong(2, ref.id)
                    stmt.step()
                }
                is MemberRef.App -> conn.prepare("UPDATE apps SET last_played_at = ? WHERE id = ?").use { stmt ->
                    stmt.bindLong(1, timestamp)
                    stmt.bindLong(2, ref.id)
                    stmt.step()
                }
                null -> orphan("recently_played", path)
            }
        }
    }

    private fun importOrdering(collectionIdsByStem: Map<String, Long>) {
        for ((index, rawTag) in readLegacyOrderingFile("platform_order.txt").withIndex()) {
            val tag = rawTag.uppercase()
            conn.prepare("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)").use { stmt ->
                stmt.bindText(1, tag)
                stmt.bindText(2, tag)
                stmt.step()
            }
            conn.prepare("UPDATE platforms SET sort_order = ? WHERE tag = ?").use { stmt ->
                stmt.bindLong(1, index.toLong())
                stmt.bindText(2, tag)
                stmt.step()
            }
        }
        for ((index, stem) in readLegacyOrderingFile("collection_order.txt").withIndex()) {
            val id = collectionIdsByStem[stem] ?: continue
            conn.prepare("UPDATE collections SET sort_order = ? WHERE id = ?").use { stmt ->
                stmt.bindLong(1, index.toLong())
                stmt.bindLong(2, id)
                stmt.step()
            }
        }
    }

    private fun resolveLegacyPath(
        absolutePath: String,
        romIdsByRelative: Map<String, Long>,
        toolNames: Map<String, Long>,
        portNames: Map<String, Long>,
    ): MemberRef? {
        val relative = relativizeRom(File(absolutePath))
        if (relative != null) {
            romIdsByRelative[relative]?.let { return MemberRef.Rom(it) }
        }
        val toolsRoot = toolsDir.absolutePath + File.separator
        val portsRoot = portsDir.absolutePath + File.separator
        if (absolutePath.startsWith(toolsRoot)) {
            toolNames[File(absolutePath).nameWithoutExtension]?.let { return MemberRef.App(it) }
        }
        if (absolutePath.startsWith(portsRoot)) {
            portNames[File(absolutePath).nameWithoutExtension]?.let { return MemberRef.App(it) }
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

    private fun migrateLegacyCollectionsToHashedNames() {
        if (!collectionsDir.exists()) return
        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return
        val needsMigration = files.any { file ->
            val stem = file.nameWithoutExtension
            if (stem.equals("Favorites", ignoreCase = true)) return@any false
            val idx = stem.lastIndexOf('_')
            if (idx < 0) return@any true
            val suffix = stem.substring(idx + 1)
            !(suffix.length == 4 && suffix.all { it in '0'..'9' || it in 'a'..'f' })
        }
        if (!needsMigration) return

        val renameMap = mutableMapOf<String, String>()
        val existingStems = files.map { it.nameWithoutExtension }.toMutableSet()

        for (file in files) {
            val oldStem = file.nameWithoutExtension
            if (oldStem.equals("Favorites", ignoreCase = true)) continue
            val idx = oldStem.lastIndexOf('_')
            val alreadyHashed = idx >= 0 && oldStem.substring(idx + 1).let { s ->
                s.length == 4 && s.all { it in '0'..'9' || it in 'a'..'f' }
            }
            if (alreadyHashed) continue

            val hash = Collection.generateUniqueHash(existingStems, oldStem)
            val newStem = "${oldStem}_$hash"
            val newFile = File(collectionsDir, "$newStem.txt")
            if (file.renameTo(newFile)) {
                renameMap[oldStem] = newStem
                existingStems.remove(oldStem)
                existingStems.add(newStem)
            }
        }

        if (renameMap.isEmpty()) return

        if (collectionParentsFile.exists()) {
            try {
                val updated = collectionParentsFile.readLines().map { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || '=' !in trimmed) return@map line
                    val (child, parent) = trimmed.split('=', limit = 2)
                    val newChild = renameMap[child.trim()] ?: child.trim()
                    val newParent = renameMap[parent.trim()] ?: parent.trim()
                    "$newChild=$newParent"
                }
                collectionParentsFile.writeText(updated.joinToString("\n") + "\n")
            } catch (_: IOException) { }
        }

        val orderFile = File(orderingDir, "collection_order.txt")
        if (orderFile.exists()) {
            try {
                val updated = orderFile.readLines().map { line ->
                    val trimmed = line.trim()
                    renameMap[trimmed] ?: trimmed
                }
                orderFile.writeText(updated.joinToString("\n") + "\n")
            } catch (_: IOException) { }
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
            File(cannoliRoot, "Config/Launch Scripts/Tools"),
            File(cannoliRoot, "Config/Launch Scripts/Ports"),
            File(cannoliRoot, "Config/Cache/.platform_cache.json"),
            File(cannoliRoot, "Config/Cache/.game_cache"),
        )
        for (src in candidates) {
            if (!src.exists()) continue
            val dest = File(backupDir, src.relativeTo(cannoliRoot).path)
            try {
                dest.parentFile?.mkdirs()
                if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                else src.copyTo(dest, overwrite = true)
                if (src.isDirectory) src.deleteRecursively() else src.delete()
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
        conn.prepare("SELECT COUNT(*) FROM roms").use { it.step(); it.getInt(0) }

    private fun countApps(): Int =
        conn.prepare("SELECT COUNT(*) FROM apps").use { it.step(); it.getInt(0) }

    private fun orphan(source: String, value: String) {
        orphans++
        ScanLog.write("orphan $source: $value")
    }
}
