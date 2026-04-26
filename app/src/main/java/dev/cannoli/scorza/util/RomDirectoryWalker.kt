package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.CannoliPaths
import java.io.File

/**
 * Walks a ROM directory and returns the in-memory list of ROMs that should exist for a platform.
 * Pure: no DB, no caching beyond what the lookups inject. Honors ignore lists, m3u/cue dir launches,
 * disc grouping, name-map overrides, and tag/region splitting.
 */
class RomDirectoryWalker(
    private val cannoliRoot: File,
    private val romDirectory: File,
    private val arcadeTitleLookup: ArcadeTitleLookup,
) {
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
        val discPaths: List<String>?,
    )

    fun loadIgnoreLists(assets: AssetManager) {
        val paths = CannoliPaths(cannoliRoot)
        seedFromAsset(assets, "ignore_extensions_roms.txt", paths.ignoreExtensionsRoms)
        seedFromAsset(assets, "ignore_files_roms.txt", paths.ignoreFilesRoms)
        ignoredExtensions = readSetLowercase(paths.ignoreExtensionsRoms) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(paths.ignoreFilesRoms) { it }
    }

    /** Returns null when the platform directory does not exist. */
    fun walk(platformTag: String, isArcade: Boolean): WalkResult? {
        val tag = platformTag.uppercase()
        val tagDir = resolveTagDir(tag) ?: return null
        val out = mutableListOf<ScannedRom>()
        scanDir(tagDir, "$tag${File.separator}", isArcade, out, depth = 0)
        return WalkResult(tagDir = tagDir, mtime = computeTreeMtime(tagDir), roms = out)
    }

    fun computeTreeMtime(dir: File): Long {
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

    fun resolveTagDir(tag: String): File? {
        val direct = File(romDirectory, tag)
        if (direct.exists()) return direct
        return romDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) }
    }

    fun invalidateNameMap(tagDir: File) = arcadeTitleLookup.invalidate(tagDir)

    data class WalkResult(val tagDir: File, val mtime: Long, val roms: List<ScannedRom>)

    private data class DirLaunch(val file: File, val discFiles: List<File>?)
    private data class PendingRom(val relativePath: String, val rawName: String, val sourceFileName: String, val discPaths: List<String>?)

    private fun scanDir(
        dir: File,
        relPrefix: String,
        isArcade: Boolean,
        out: MutableList<ScannedRom>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
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

        val nameOverrides = arcadeTitleLookup.mapFor(dir, fallbackToArcade = isArcade)
        for (p in pending) {
            val override = nameOverrides[p.sourceFileName]
            val (displayName, tags) = if (override != null) override to null else splitNameAndTags(p.rawName)
            out.add(ScannedRom(p.relativePath, displayName, tags, p.discPaths))
        }
    }

    private fun splitNameAndTags(rawName: String): Pair<String, String?> {
        val base = tagRegex.replace(rawName, "").trim()
        if (base.isEmpty() || base == rawName) return rawName to null
        val tags = tagRegex.findAll(rawName).joinToString(" ") { it.value.trim() }.takeIf { it.isNotBlank() }
        return base to tags
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
        const val MAX_DEPTH = 16
    }
}
