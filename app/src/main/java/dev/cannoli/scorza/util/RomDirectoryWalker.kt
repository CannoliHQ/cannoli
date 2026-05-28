package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import java.io.File

/**
 * Walks a ROM directory and returns the in-memory list of ROMs that should exist for a platform.
 * Reorganizes loose multi-disc sets into per-game subfolders with a generated `<base>.m3u` so
 * libretro cores resolve disc paths correctly. Honors ignore lists, m3u/cue dir launches,
 * disc grouping, name-map overrides, and tag/region splitting.
 */
class RomDirectoryWalker(
    private val pathsProvider: CannoliPathsProvider,
    private val assets: AssetManager,
    private val arcadeTitleLookup: ArcadeTitleLookup,
) {
    private val cannoliRoot: File get() = pathsProvider.root
    private val romDirectory: File get() = pathsProvider.romDir

    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")
    private val cueFileLineRegex = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\w+\s*$""", RegexOption.IGNORE_CASE)

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()
    @Volatile private var ignoreListsLoaded = false

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
        val discPaths: List<String>?,
    )

    /** A multi-disc set that the organizer relocated; `oldRelPath` is what the DB previously
     *  referenced (the first disc's path), `newRelPath` is the generated m3u. */
    data class RekeyMove(val oldRelPath: String, val newRelPath: String)

    private fun ensureIgnoreLists() {
        if (ignoreListsLoaded) return
        val paths = CannoliPaths(cannoliRoot)
        seedFromAsset(assets, "ignore_extensions_roms.txt", paths.ignoreExtensionsRoms)
        seedFromAsset(assets, "ignore_files_roms.txt", paths.ignoreFilesRoms)
        ignoredExtensions = readSetLowercase(paths.ignoreExtensionsRoms) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(paths.ignoreFilesRoms) { it }
        ignoreListsLoaded = true
    }

    /** Returns null when the platform directory does not exist. */
    fun walk(platformTag: String, isArcade: Boolean): WalkResult? {
        ensureIgnoreLists()
        val tag = platformTag.uppercase()
        val tagDir = resolveTagDir(tag) ?: return null
        val rekeys = mutableListOf<RekeyMove>()
        organizeDir(tagDir, "$tag${File.separator}", tag, rekeys, depth = 0)
        val out = mutableListOf<ScannedRom>()
        scanDir(tagDir, "$tag${File.separator}", isArcade, out, depth = 0)
        return WalkResult(tagDir = tagDir, mtime = computeTreeMtime(tagDir), roms = out, rekeys = rekeys)
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

    data class WalkResult(
        val tagDir: File,
        val mtime: Long,
        val roms: List<ScannedRom>,
        val rekeys: List<RekeyMove> = emptyList(),
    )

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
                val (displayName, tags) = splitNameAndTags(subdir.name)
                out.add(ScannedRom(launchRel, displayName, tags, discRels))
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
            val rawName = if (file.name.endsWith(".p8.png", ignoreCase = true)) {
                file.name.dropLast(".p8.png".length)
            } else {
                file.nameWithoutExtension
            }
            pending.add(PendingRom("$relPrefix${file.name}", rawName, file.name, null))
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

    private fun organizeDir(
        dir: File,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            organizeDir(subdir, "$relPrefix${subdir.name}${File.separator}", tag, moves, depth + 1)
        }

        val romFiles = files.filterNot { isIgnoredExtension(it) }
        val m3uByBase = romFiles.filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val processed = mutableSetOf<File>()
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        for ((baseName, groupFiles) in discGroups) {
            val byStem = groupFiles.groupBy { it.nameWithoutExtension }
            if (byStem.size <= 1) continue
            if (m3uByBase[baseName] != null) continue
            if (organizeMultiDisc(dir, baseName, byStem, romFiles, relPrefix, tag, moves)) {
                processed.addAll(groupFiles)
            }
        }

        val remainingSiblings = romFiles.filter { it !in processed }
        val looseCues = remainingSiblings.filter {
            it.extension.equals("cue", ignoreCase = true) &&
                !discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        for (cue in looseCues) {
            organizeSingleCue(dir, cue, remainingSiblings, relPrefix, tag, moves)
        }
    }

    private fun organizeMultiDisc(
        parent: File,
        baseName: String,
        discsByStem: Map<String, List<File>>,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        if (parent.name == baseName) return false
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return false

        val primaries = discsByStem.values.map { pickPrimary(it) }.sortedBy { it.name }
        val allDiscFiles = discsByStem.values.flatten()
        val toMove = linkedSetOf<File>().apply {
            addAll(allDiscFiles)
            for (file in allDiscFiles) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }

        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val m3uFile = File(subdir, "$baseName.m3u")
        try {
            m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
        } catch (e: Throwable) {
            ScanLog.write("organize $tag: failed to write $baseName.m3u: ${e.message}")
            rollback(moved, subdir)
            return false
        }

        val sortedAll = allDiscFiles.sortedBy { it.name }
        val firstStem = sortedAll.first().nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)

        val oldRel = "$relPrefix${sortedAll.first().name}"
        val newRel = "$relPrefix$baseName${File.separator}${m3uFile.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled $baseName (${primaries.size} discs, ${toMove.size - primaries.size} companions)")
        return true
    }

    private fun organizeSingleCue(
        parent: File,
        cue: File,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ) {
        val baseName = cue.nameWithoutExtension
        if (parent.name == baseName) return
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return

        val toMove = linkedSetOf<File>().apply {
            add(cue)
            addAll(stemSiblings(cue, siblings))
            addAll(parseCueReferencedFiles(cue))
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return

        val oldRel = "$relPrefix${cue.name}"
        val newRel = "$relPrefix$baseName${File.separator}${cue.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled single-disc $baseName (${toMove.size - 1} companions)")
    }

    private fun createSubdir(subdir: File, tag: String, baseName: String): Boolean {
        if (subdir.exists()) {
            ScanLog.write("organize $tag: skip $baseName (target subfolder already exists)")
            return false
        }
        if (!subdir.mkdir()) {
            ScanLog.write("organize $tag: failed to mkdir $baseName")
            return false
        }
        return true
    }

    private fun moveAll(
        toMove: Collection<File>,
        subdir: File,
        moved: MutableList<Pair<File, File>>,
        tag: String,
        baseName: String,
    ): Boolean {
        for (file in toMove) {
            val target = File(subdir, file.name)
            if (file.renameTo(target)) {
                moved.add(file to target)
            } else {
                ScanLog.write("organize $tag: failed to move ${file.name} into $baseName/")
                rollback(moved, subdir)
                return false
            }
        }
        return true
    }

    private fun rollback(moved: List<Pair<File, File>>, subdir: File) {
        for ((src, dst) in moved) dst.renameTo(src)
        subdir.delete()
    }

    private fun pickPrimary(files: List<File>): File {
        return files.minByOrNull { f ->
            val idx = PRIMARY_DISC_EXTENSIONS.indexOf(f.extension.lowercase())
            if (idx >= 0) idx else Int.MAX_VALUE
        } ?: files.first()
    }

    private fun stemSiblings(disc: File, siblings: List<File>): List<File> {
        val stem = disc.nameWithoutExtension
        return siblings.filter { other ->
            if (other == disc || other.isDirectory) return@filter false
            val otherStem = other.nameWithoutExtension
            otherStem == stem ||
                otherStem.startsWith("$stem ") ||
                otherStem.startsWith("$stem.") ||
                other.name.startsWith("$stem.")
        }
    }

    private fun parseCueReferencedFiles(cue: File): List<File> {
        val parent = cue.parentFile ?: return emptyList()
        return try {
            cue.useLines { lines ->
                lines.mapNotNull { line ->
                    val match = cueFileLineRegex.find(line) ?: return@mapNotNull null
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    File(parent, name).takeIf { it.isFile }
                }.toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun migrateSidecarFiles(tag: String, fromStem: String, toStem: String) {
        val paths = CannoliPaths(cannoliRoot)
        renameStemMatchedFiles(paths.savesFor(tag), fromStem, toStem)
        val statesTagDir = paths.saveStatesFor(tag)
        renameStemMatchedFiles(statesTagDir, fromStem, toStem)
        val stateSub = File(statesTagDir, fromStem)
        val stateSubTarget = File(statesTagDir, toStem)
        if (stateSub.isDirectory && !stateSubTarget.exists() && stateSub.renameTo(stateSubTarget)) {
            renameStemMatchedFiles(stateSubTarget, fromStem, toStem)
        }
    }

    private fun renameStemMatchedFiles(dir: File, fromStem: String, toStem: String) {
        if (!dir.isDirectory) return
        val matches = dir.listFiles()?.filter { f ->
            if (!f.isFile) return@filter false
            val n = f.nameWithoutExtension
            n == fromStem || n.startsWith("$fromStem.")
        } ?: return
        for (f in matches) {
            val newName = toStem + f.name.substring(fromStem.length)
            val target = File(dir, newName)
            if (!target.exists()) f.renameTo(target)
        }
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
        val PRIMARY_DISC_EXTENSIONS = listOf("cue", "chd", "gdi", "toc", "ccd", "iso", "img", "pbp", "bin")
    }
}
