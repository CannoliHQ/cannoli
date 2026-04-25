package dev.cannoli.scorza.library

import androidx.sqlite.execSQL
import dev.cannoli.scorza.db.CannoliDatabase
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

    fun loadIgnoreLists(assets: android.content.res.AssetManager) {
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
        val collected = mutableListOf<ScannedRom>()
        scanDir(tagDir, "$tag${File.separator}", isArcade, collected)
        artwork.invalidate(tag)
        nameMap.invalidate(tagDir)
        val counts = sync(tag, collected)
        ScanLog.write("scanPlatform $tag: +${counts.inserted} -${counts.removed} ~${counts.updated}")
        return counts
    }

    data class SyncCounts(val inserted: Int, val updated: Int, val removed: Int)

    private data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val discPaths: List<String>?,
    )

    private data class DirLaunch(val file: File, val discFiles: List<File>?)

    private fun scanDir(
        dir: File,
        relPrefix: String,
        isArcade: Boolean,
        out: MutableList<ScannedRom>,
    ) {
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            val launch = findDirLaunchFile(subdir)
            if (launch != null) {
                val launchRel = "$relPrefix${subdir.name}${File.separator}${launch.file.name}"
                val discRels = launch.discFiles?.map { "$relPrefix${subdir.name}${File.separator}${it.name}" }
                out.add(ScannedRom(launchRel, subdir.name, discRels))
            } else if (subdir.listFiles()?.any { !it.name.startsWith(".") } == true) {
                scanDir(subdir, "$relPrefix${subdir.name}${File.separator}", isArcade, out)
            }
        }

        if (files.isEmpty()) return

        val romFiles = files.filterNot { isIgnoredExtension(it) }
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        val m3uByBase = romFiles
            .filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        data class PendingRom(val relativePath: String, val rawName: String, val sourceFileName: String, val discPaths: List<String>?)
        val suppressed = mutableSetOf<String>()
        val pending = mutableListOf<PendingRom>()
        for ((baseName, discs) in discGroups) {
            if (discs.size <= 1) continue
            val sorted = discs.sortedBy { it.name }
            val m3u = m3uByBase[baseName]
            if (m3u != null) {
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
        val stripped = stripTagsForDir(pending.map { it.rawName })
        for ((index, p) in pending.withIndex()) {
            val displayName = nameOverrides[p.sourceFileName] ?: stripped[index]
            out.add(ScannedRom(p.relativePath, displayName, p.discPaths))
        }
    }

    private fun stripTagsForDir(names: List<String>): List<String> {
        val stripped = names.map { it to tagRegex.replace(it, "").trim() }
        val baseCounts = mutableMapOf<String, Int>()
        for ((_, base) in stripped) baseCounts[base] = (baseCounts[base] ?: 0) + 1
        return stripped.map { (raw, base) ->
            if (base.isEmpty() || (baseCounts[base] ?: 0) > 1) raw else base
        }
    }

    private fun sync(tag: String, scanned: List<ScannedRom>): SyncCounts {
        data class ExistingRow(val id: Long, val displayName: String, val discPaths: String?)
        val existing = mutableMapOf<String, ExistingRow>()
        db.conn.prepare("SELECT id, path, display_name, disc_paths FROM roms WHERE platform_tag = ?").use { stmt ->
            stmt.bindText(1, tag)
            while (stmt.step()) {
                val path = stmt.getText(1)
                existing[path] = ExistingRow(
                    id = stmt.getLong(0),
                    displayName = stmt.getText(2),
                    discPaths = if (stmt.isNull(3)) null else stmt.getText(3),
                )
            }
        }

        val scannedByPath = scanned.associateBy { it.relativePath }
        var inserted = 0
        var updated = 0
        var removed = 0

        db.conn.execSQL("BEGIN")
        try {
            for (rom in scanned) {
                val current = existing[rom.relativePath]
                val discJson = rom.discPaths?.let { JSONArray(it).toString() }
                if (current == null) {
                    db.conn.prepare("INSERT INTO roms (path, platform_tag, display_name, disc_paths) VALUES (?, ?, ?, ?)").use { stmt ->
                        stmt.bindText(1, rom.relativePath)
                        stmt.bindText(2, tag)
                        stmt.bindText(3, rom.displayName)
                        if (discJson != null) stmt.bindText(4, discJson) else stmt.bindNull(4)
                        stmt.step()
                    }
                    inserted++
                } else if (current.displayName != rom.displayName || current.discPaths != discJson) {
                    db.conn.prepare("UPDATE roms SET display_name = ?, disc_paths = ? WHERE id = ?").use { stmt ->
                        stmt.bindText(1, rom.displayName)
                        if (discJson != null) stmt.bindText(2, discJson) else stmt.bindNull(2)
                        stmt.bindLong(3, current.id)
                        stmt.step()
                    }
                    updated++
                }
            }
            for ((path, row) in existing) {
                if (path in scannedByPath) continue
                db.conn.prepare("DELETE FROM roms WHERE id = ?").use { stmt ->
                    stmt.bindLong(1, row.id)
                    stmt.step()
                }
                removed++
            }
            db.conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            db.conn.execSQL("ROLLBACK")
            throw t
        }

        return SyncCounts(inserted, updated, removed)
    }

    private fun clearPlatform(tag: String): SyncCounts {
        val count = db.conn.prepare("SELECT COUNT(*) FROM roms WHERE platform_tag = ?").use { stmt ->
            stmt.bindText(1, tag)
            stmt.step(); stmt.getInt(0)
        }
        if (count == 0) return SyncCounts(0, 0, 0)
        db.conn.prepare("DELETE FROM roms WHERE platform_tag = ?").use { stmt ->
            stmt.bindText(1, tag)
            stmt.step()
        }
        return SyncCounts(0, 0, count)
    }

    fun ensureReservedPlatformTag(tag: String) = ensurePlatformRow(tag)

    private fun ensurePlatformRow(tag: String) {
        db.conn.prepare("INSERT OR IGNORE INTO platforms (tag, display_name) VALUES (?, ?)").use { stmt ->
            stmt.bindText(1, tag)
            stmt.bindText(2, tag)
            stmt.step()
        }
    }

    private fun resolveTagDir(tag: String): File? {
        val direct = File(romDirectory, tag)
        if (direct.exists()) return direct
        return romDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) }
    }

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() }?.let { return DirLaunch(it, null) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() }?.let { return DirLaunch(it, null) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) }?.let { return DirLaunch(it, null) }
        val children = dir.listFiles()?.filter { it.isFile } ?: return null
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

    private fun seedFromAsset(assets: android.content.res.AssetManager, name: String, target: File) {
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
}
