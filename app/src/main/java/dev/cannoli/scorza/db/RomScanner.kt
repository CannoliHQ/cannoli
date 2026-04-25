package dev.cannoli.scorza.db

import android.content.res.AssetManager
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.NameMapLookup
import dev.cannoli.scorza.util.ScanLog
import org.json.JSONArray
import java.io.File

class RomScanner(
    private val cannoliRoot: File,
    private val romDirectory: File,
    private val db: CannoliDatabase,
    private val nameMap: NameMapLookup,
    private val artwork: ArtworkLookup,
) {
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()

    fun loadIgnoreLists(assets: AssetManager) {
        seedFromAsset(assets, "ignore_extensions_roms.txt", File(cannoliRoot, "Config/ignore_extensions_roms.txt"))
        seedFromAsset(assets, "ignore_files_roms.txt", File(cannoliRoot, "Config/ignore_files_roms.txt"))
        ignoredExtensions = readSetLowercase(File(cannoliRoot, "Config/ignore_extensions_roms.txt")) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(File(cannoliRoot, "Config/ignore_files_roms.txt")) { it }
    }

    fun scanPlatform(platformTag: String, isArcade: Boolean = false): SyncCounts {
        val tag = platformTag.uppercase()
        ensurePlatformRow(tag)
        val tagDir = resolveTagDir(tag) ?: return clearPlatform(tag).also {
            ScanLog.write("scanPlatform $tag: no rom dir, cleared ${it.removed}")
        }
        val mtime = computeTreeMtime(tagDir)
        val storedMtime = readLastScannedMtime(tag)
        if (storedMtime != MTIME_UNSET && storedMtime == mtime) {
            return SyncCounts(0, 0, 0)
        }
        val collected = mutableListOf<ScannedRom>()
        scanDir(tagDir, "$tag${File.separator}", isArcade, collected, depth = 0)
        artwork.invalidate(tag)
        nameMap.invalidate(tagDir)
        val counts = sync(tag, collected)
        writeLastScannedMtime(tag, mtime)
        ScanLog.write("scanPlatform $tag: +${counts.inserted} -${counts.removed} ~${counts.updated}")
        return counts
    }

    fun invalidatePlatform(platformTag: String) {
        writeLastScannedMtime(platformTag.uppercase(), MTIME_UNSET)
    }

    fun ensureReservedPlatformTag(tag: String) = ensurePlatformRow(tag)

    data class SyncCounts(val inserted: Int, val updated: Int, val removed: Int)

    private data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
        val discPaths: List<String>?,
    )

    private data class DirLaunch(val file: File, val discFiles: List<File>?)

    private fun computeTreeMtime(dir: File): Long {
        var max = dir.lastModified()
        val children = dir.listFiles() ?: return max
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                val sub = computeTreeMtime(child)
                if (sub > max) max = sub
            }
        }
        return max
    }

    private fun readLastScannedMtime(tag: String): Long = db.conn.query(
        "SELECT last_scanned_mtime FROM platforms WHERE tag = ?"
    ) { stmt ->
        stmt.bindText(1, tag)
        if (stmt.step()) stmt.getLong(0) else MTIME_UNSET
    }

    private fun writeLastScannedMtime(tag: String, mtime: Long) = db.conn.execute(
        "UPDATE platforms SET last_scanned_mtime = ? WHERE tag = ?",
        mtime, tag,
    )

    private fun scanDir(
        dir: File,
        relPrefix: String,
        isArcade: Boolean,
        out: MutableList<ScannedRom>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) {
            ScanLog.write("WARN scanDir hit max depth at ${dir.absolutePath}")
            return
        }
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            val launch = findDirLaunchFile(subdir)
            if (launch != null) {
                val launchRel = "$relPrefix${subdir.name}${File.separator}${launch.file.name}"
                val discRels = launch.discFiles?.map { "$relPrefix${subdir.name}${File.separator}${it.name}" }
                out.add(ScannedRom(launchRel, subdir.name, null, discRels))
            } else if (subdir.listFiles()?.any { !it.name.startsWith(".") } == true) {
                scanDir(subdir, "$relPrefix${subdir.name}${File.separator}", isArcade, out, depth + 1)
            }
        }

        if (files.isEmpty()) return

        val romFiles = files.filterNot { isIgnoredExtension(it) }
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        val m3uByBase = romFiles
            .filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val suppressed = mutableSetOf<String>()
        val pending = mutableListOf<PendingRom>()
        for ((baseName, discs) in discGroups) {
            if (discs.size <= 1) continue
            val sorted = discs.sortedBy { it.name }
            if (m3uByBase[baseName] != null) {
                sorted.forEach { suppressed.add(it.absolutePath) }
            } else {
                val discRels = sorted.map { "$relPrefix${it.name}" }
                pending.add(PendingRom("$relPrefix${sorted.first().name}", baseName, sorted.first().name, discRels))
                sorted.forEach { suppressed.add(it.absolutePath) }
            }
        }
        for (file in romFiles) {
            if (file.absolutePath in suppressed) continue
            pending.add(PendingRom("$relPrefix${file.name}", file.nameWithoutExtension, file.name, null))
        }

        val nameOverrides = nameMap.mapFor(dir, fallbackToArcade = isArcade)
        for (p in pending) {
            val override = nameOverrides[p.sourceFileName]
            val (displayName, tags) = if (override != null) override to null else splitNameAndTags(p.rawName)
            out.add(ScannedRom(p.relativePath, displayName, tags, p.discPaths))
        }
    }

    private data class PendingRom(val relativePath: String, val rawName: String, val sourceFileName: String, val discPaths: List<String>?)

    private fun splitNameAndTags(rawName: String): Pair<String, String?> {
        val base = tagRegex.replace(rawName, "").trim()
        if (base.isEmpty() || base == rawName) return rawName to null
        val tags = tagRegex.findAll(rawName).joinToString(" ") { it.value.trim() }.takeIf { it.isNotBlank() }
        return base to tags
    }

    private fun sync(tag: String, scanned: List<ScannedRom>): SyncCounts {
        data class ExistingRow(val id: Long, val displayName: String, val tags: String?, val discPaths: String?)
        val existing = mutableMapOf<String, ExistingRow>()
        db.conn.query("SELECT id, path, display_name, tags, disc_paths FROM roms WHERE platform_tag = ?") { stmt ->
            stmt.bindText(1, tag)
            while (stmt.step()) {
                existing[stmt.getText(1)] = ExistingRow(
                    id = stmt.getLong(0),
                    displayName = stmt.getText(2),
                    tags = if (stmt.isNull(3)) null else stmt.getText(3),
                    discPaths = if (stmt.isNull(4)) null else stmt.getText(4),
                )
            }
        }

        val scannedByPath = scanned.associateBy { it.relativePath }
        var inserted = 0
        var updated = 0
        var removed = 0

        db.conn.transaction {
            db.conn.prepare("INSERT INTO roms (path, platform_tag, display_name, tags, disc_paths) VALUES (?, ?, ?, ?, ?)").use { insertStmt ->
                db.conn.prepare("UPDATE roms SET display_name = ?, tags = ?, disc_paths = ? WHERE id = ?").use { updateStmt ->
                    db.conn.prepare("DELETE FROM roms WHERE id = ?").use { deleteStmt ->
                        for (rom in scanned) {
                            val current = existing[rom.relativePath]
                            val discJson = rom.discPaths?.let { JSONArray(it).toString() }
                            if (current == null) {
                                insertStmt.reset()
                                insertStmt.bindText(1, rom.relativePath)
                                insertStmt.bindText(2, tag)
                                insertStmt.bindText(3, rom.displayName)
                                if (rom.tags != null) insertStmt.bindText(4, rom.tags) else insertStmt.bindNull(4)
                                if (discJson != null) insertStmt.bindText(5, discJson) else insertStmt.bindNull(5)
                                insertStmt.step()
                                inserted++
                            } else if (current.displayName != rom.displayName || current.tags != rom.tags || current.discPaths != discJson) {
                                updateStmt.reset()
                                updateStmt.bindText(1, rom.displayName)
                                if (rom.tags != null) updateStmt.bindText(2, rom.tags) else updateStmt.bindNull(2)
                                if (discJson != null) updateStmt.bindText(3, discJson) else updateStmt.bindNull(3)
                                updateStmt.bindLong(4, current.id)
                                updateStmt.step()
                                updated++
                            }
                        }
                        for ((path, row) in existing) {
                            if (path in scannedByPath) continue
                            deleteStmt.reset()
                            deleteStmt.bindLong(1, row.id)
                            deleteStmt.step()
                            removed++
                        }
                    }
                }
            }
        }

        return SyncCounts(inserted, updated, removed)
    }

    private fun clearPlatform(tag: String): SyncCounts {
        val count = db.conn.query("SELECT COUNT(*) FROM roms WHERE platform_tag = ?") { stmt ->
            stmt.bindText(1, tag)
            stmt.step(); stmt.getInt(0)
        }
        if (count == 0) return SyncCounts(0, 0, 0)
        db.conn.execute("DELETE FROM roms WHERE platform_tag = ?", tag)
        return SyncCounts(0, 0, count)
    }

    private fun ensurePlatformRow(tag: String) = db.conn.execute(
        "INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)",
        tag, tag,
    )

    private fun resolveTagDir(tag: String): File? {
        val direct = File(romDirectory, tag)
        if (direct.exists()) return direct
        return romDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) }
    }

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        val children = dir.listFiles()?.filter { it.isFile && !isIgnored(it) } ?: return null
        val discs = children.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        if (discs.size > 1) {
            val sorted = discs.sortedBy { it.name }
            return DirLaunch(sorted.first(), sorted)
        }
        return null
    }

    private fun isIgnored(file: File): Boolean =
        file.name.lowercase() in ignoredFiles ||
            (file.isFile && file.extension.lowercase() in ignoredExtensions)

    private fun isIgnoredExtension(file: File): Boolean =
        file.extension.lowercase() in ignoredExtensions

    private fun seedFromAsset(assets: AssetManager, name: String, target: File) {
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (_: Throwable) { }
    }

    private fun readSetLowercase(file: File, transform: (String) -> String): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().map { transform(it.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    private companion object {
        const val MTIME_UNSET = 0L
        const val MAX_DEPTH = 16
    }
}
