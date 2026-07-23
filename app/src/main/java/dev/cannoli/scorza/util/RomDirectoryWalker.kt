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
    private val cueFileLineRegex = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\w+\s*$""", RegexOption.IGNORE_CASE)

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()
    @Volatile private var ignoreListsLoaded = false

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
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
        return WalkResult(tagDir = tagDir, roms = out, rekeys = rekeys)
    }

    fun resolveTagDir(tag: String): File? {
        val direct = File(romDirectory, tag)
        if (direct.exists()) return direct
        return romDirectory.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) }
    }

    fun invalidateNameMap(tagDir: File) = arcadeTitleLookup.invalidate(tagDir)

    /** Every category (non-game) folder under the platform, as platform-relative slash paths. */
    fun categoryFolders(platformTag: String): List<String> {
        ensureIgnoreLists()
        val tagDir = resolveTagDir(platformTag.uppercase()) ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val subdirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
            for (sub in subdirs) {
                if (findDirLaunchFile(sub) != null) continue
                val rel = if (prefix.isEmpty()) sub.name else "$prefix/${sub.name}"
                out.add(rel)
                walk(sub, rel, depth + 1)
            }
        }
        walk(tagDir, "", 0)
        return out.sortedWith(compareBy { it.lowercase() })
    }

    /** The dedicated on-disk folder for the game launched by [primaryFile], or null when the game
     *  is a loose file not contained in its own folder. */
    fun gameDirectory(primaryFile: File): File? {
        val parent = primaryFile.parentFile ?: return null
        if (parent.parentFile == romDirectory) return null
        val launch = findDirLaunchFile(parent) ?: return null
        return if (launch.file == primaryFile) parent else null
    }

    /** Every on-disk file that makes up the game launched by [primaryFile]. */
    fun gameFiles(primaryFile: File): List<File> {
        gameDirectory(primaryFile)?.let { dir ->
            return dir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }
                ?: listOf(primaryFile)
        }
        if (primaryFile.extension.equals("m3u", ignoreCase = true)) {
            val parent = primaryFile.parentFile
            if (parent != null) {
                val out = linkedSetOf(primaryFile)
                for (entry in readM3uEntries(primaryFile)) {
                    val disc = File(parent, entry)
                    if (disc.isFile) {
                        out.add(disc)
                        if (disc.extension.equals("cue", ignoreCase = true)) {
                            out.addAll(parseCueReferencedFiles(disc))
                        }
                    }
                }
                return out.toList()
            }
        }
        return listOf(primaryFile)
    }

    enum class RenameOutcome { RENAMED, NAME_TAKEN, FAILED }

    /** Renames a game. For a single-file game, renames the rom file. For a folder game, cascades:
     *  the folder, the launch file, the disc files, and the m3u contents all take the new name. */
    fun renameGame(primaryFile: File, newName: String): RenameOutcome {
        if (newName.isBlank()) return RenameOutcome.FAILED

        val dir = gameDirectory(primaryFile)
        if (dir == null) {
            val currentBase = primaryFile.nameWithoutExtension
            if (newName.equals(currentBase, ignoreCase = true)) return RenameOutcome.RENAMED
            val ext = primaryFile.extension
            val target = File(primaryFile.parentFile, if (ext.isEmpty()) newName else "$newName.$ext")
            if (target.exists()) return RenameOutcome.NAME_TAKEN
            return if (primaryFile.renameTo(target)) RenameOutcome.RENAMED else RenameOutcome.FAILED
        }

        val oldBase = dir.name
        if (newName.equals(oldBase, ignoreCase = true)) return RenameOutcome.RENAMED
        val newDir = File(dir.parentFile, newName)
        if (newDir.exists()) return RenameOutcome.NAME_TAKEN

        if (!dir.renameTo(newDir)) return RenameOutcome.FAILED

        val prefixed = newDir.listFiles { f ->
            f.isFile && f.name.startsWith(oldBase) && run {
                val rest = f.name.substring(oldBase.length)
                rest.isEmpty() || rest[0] == '.' || rest[0] == '(' || rest[0] == ' '
            }
        }.orEmpty()
        val renamed = mutableListOf<Pair<File, File>>()
        for (file in prefixed) {
            val target = File(newDir, newName + file.name.removePrefix(oldBase))
            if (file.renameTo(target)) {
                renamed.add(file to target)
            } else {
                for ((from, to) in renamed.asReversed()) to.renameTo(from)
                newDir.renameTo(dir)
                return RenameOutcome.FAILED
            }
        }

        val m3u = File(newDir, "$newName.m3u")
        if (m3u.exists()) {
            val discs = newDir.listFiles { f ->
                f.isFile && discRegex.containsMatchIn(f.nameWithoutExtension)
            }.orEmpty().toList()
            val primaries = discs.groupBy { it.nameWithoutExtension }
                .values.map { pickPrimary(it) }.sortedBy { it.name }
            m3u.writeText(primaries.joinToString("\n") { it.name } + "\n")
        }

        return RenameOutcome.RENAMED
    }

    data class WalkResult(
        val tagDir: File,
        val roms: List<ScannedRom>,
        val rekeys: List<RekeyMove> = emptyList(),
    )

    private data class DirLaunch(val file: File)

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
                val (displayName, tags) = RomNaming.splitNameAndTags(subdir.name)
                out.add(ScannedRom(launchRel, displayName, tags))
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

        // Discs belonging to a loose m3u in the same directory are represented by that m3u.
        val suppressed = discGroups
            .filter { (baseName, discs) -> discs.size > 1 && m3uByBase[baseName] != null }
            .values.flatten()
            .mapTo(mutableSetOf()) { it.absolutePath }

        val nameOverrides = arcadeTitleLookup.mapFor(dir, fallbackToArcade = isArcade)
        for (file in romFiles) {
            if (file.absolutePath in suppressed) continue
            val override = nameOverrides[file.name]
            val rawName = if (file.name.endsWith(".p8.png", ignoreCase = true)) {
                file.name.dropLast(".p8.png".length)
            } else {
                file.nameWithoutExtension
            }
            val (displayName, tags) = if (override != null) override to null else RomNaming.splitNameAndTags(rawName)
            out.add(ScannedRom("$relPrefix${file.name}", displayName, tags))
        }
    }

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) && !isIgnored(it) }?.let { return DirLaunch(it) }
        val children = dir.listFiles()?.filter { it.isFile && !isIgnored(it) } ?: return null
        val discs = children.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        if (discs.size > 1) {
            return DirLaunch(discs.sortedBy { it.name }.first())
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
            val existingSubdir = File(dir, baseName)
            if (existingSubdir.isDirectory) {
                if (mergeLooseDiscsIntoBundle(baseName, existingSubdir, groupFiles, romFiles, tag)) {
                    processed.addAll(groupFiles)
                }
                continue
            }
            if (byStem.size <= 1) continue
            val looseM3u = m3uByBase[baseName]
            if (looseM3u != null) {
                if (relocateLooseM3uSet(dir, baseName, looseM3u, byStem, romFiles, relPrefix, tag, moves)) {
                    processed.addAll(groupFiles)
                    processed.add(looseM3u)
                }
                continue
            }
            if (dir.name == baseName) {
                if (writeInPlaceM3u(dir, baseName, byStem, relPrefix, tag, moves)) {
                    processed.addAll(groupFiles)
                }
                continue
            }
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

    private fun readM3uEntries(m3u: File): List<String> = runCatching {
        m3u.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }.getOrDefault(emptyList())

    // Relocates a loose m3u set into its own folder; skips m3us whose entries are not flat siblings.
    private fun relocateLooseM3uSet(
        parent: File,
        baseName: String,
        m3u: File,
        discsByStem: Map<String, List<File>>,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        if (parent.name == baseName) return false
        val entries = readM3uEntries(m3u)
        if (entries.isEmpty() || entries.any { !File(parent, it).isFile }) return false

        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return false

        val allDiscFiles = discsByStem.values.flatten()
        val toMove = linkedSetOf<File>().apply {
            add(m3u)
            entries.forEach { add(File(parent, it)) }
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

        val firstStem = allDiscFiles.sortedBy { it.name }.first().nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)
        moves.add(RekeyMove("$relPrefix${m3u.name}", "$relPrefix$baseName${File.separator}${m3u.name}"))
        ScanLog.write("organize $tag: relocated loose m3u set $baseName (${allDiscFiles.size} discs)")
        return true
    }

    private fun writeInPlaceM3u(
        dir: File,
        baseName: String,
        discsByStem: Map<String, List<File>>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        val allDiscFiles = discsByStem.values.flatten()
        val primaries = discsByStem.values.map { pickPrimary(it) }.sortedBy { it.name }
        if (primaries.size <= 1) return false
        val m3uFile = File(dir, "$baseName.m3u")
        try {
            m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
        } catch (e: Throwable) {
            ScanLog.write("organize $tag: failed to write in-place $baseName.m3u: ${e.message}")
            return false
        }
        val firstDisc = allDiscFiles.sortedBy { it.name }.first()
        val firstStem = firstDisc.nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)
        moves.add(RekeyMove("$relPrefix${firstDisc.name}", "$relPrefix${m3uFile.name}"))
        ScanLog.write("organize $tag: wrote in-place m3u for $baseName (${primaries.size} discs)")
        return true
    }

    // Moves late-arriving loose discs into an existing `<baseName>/` bundle and rewrites its m3u,
    // so incremental uploads (a disc landing after the bundle was already organized) still converge.
    private fun mergeLooseDiscsIntoBundle(
        baseName: String,
        subdir: File,
        looseDiscs: List<File>,
        siblings: List<File>,
        tag: String,
    ): Boolean {
        val m3uFile = File(subdir, "$baseName.m3u")
        // Also merge into a folder that already holds this game's discs but has no m3u yet.
        if (!m3uFile.exists() && !folderHoldsDiscsOf(subdir, baseName)) return false

        val toMove = linkedSetOf<File>().apply {
            addAll(looseDiscs)
            for (file in looseDiscs) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val discFiles = subdir.listFiles()?.filter {
            it.isFile && discRegex.containsMatchIn(it.nameWithoutExtension)
        }.orEmpty()
        val primaries = discFiles.groupBy { it.nameWithoutExtension }
            .values.map { pickPrimary(it) }.sortedBy { it.name }
        if (primaries.isNotEmpty()) {
            try {
                m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
            } catch (e: Throwable) {
                ScanLog.write("organize $tag: failed to rewrite $baseName.m3u: ${e.message}")
            }
        }
        for (disc in discFiles) {
            val stem = disc.nameWithoutExtension
            if (stem != baseName) migrateSidecarFiles(tag, stem, baseName)
        }
        ScanLog.write("organize $tag: merged ${looseDiscs.size} loose disc(s) into $baseName/")
        return true
    }

    private fun folderHoldsDiscsOf(subdir: File, baseName: String): Boolean =
        subdir.listFiles()?.any {
            it.isFile && discRegex.containsMatchIn(it.nameWithoutExtension) &&
                discRegex.replace(it.nameWithoutExtension, "").trim() == baseName
        } == true

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
