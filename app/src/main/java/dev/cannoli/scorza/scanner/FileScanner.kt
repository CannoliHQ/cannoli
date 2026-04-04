package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.sortedNatural
import java.io.File
import java.io.IOException

class FileScanner(
    private val cannoliRoot: File,
    private val platformResolver: PlatformResolver,
    romsDir: File? = null
) {
    private val romsDir = romsDir ?: File(cannoliRoot, "Roms")
    private val artDir = File(cannoliRoot, "Art")
    private val collectionsDir = File(cannoliRoot, "Collections")
    private val toolsDir = File(cannoliRoot, "Config/Launch Scripts/Tools")
    private val portsDir = File(cannoliRoot, "Config/Launch Scripts/Ports")

    private val favoritesLock = Any()
    @Volatile private var favoritesCache: Set<String>? = null
    private val artCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, File>>()
    private val mapCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    private val scanCache = ScanCache(cannoliRoot)
    val dirTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun scanPlatforms(): List<Platform> {
        if (!romsDir.exists()) return emptyList()

        val tagDirs = romsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        val knownDirs = tagDirs.filter { platformResolver.isKnownTag(it.name) }
        val platformCache = scanCache.loadPlatformCache()
        val newEntries = mutableMapOf<String, CachedPlatformEntry>()
        var anyStale = false

        val all = knownDirs.map { dir ->
            val tag = dir.name
            val dirMod = dir.lastModified()
            dirTimestamps[tag] = dirMod

            val cached = platformCache?.get(tag)
            val hasGames = if (cached != null && cached.lastModified == dirMod) {
                cached.hasGames
            } else {
                anyStale = true
                val files = dir.listFiles()
                files?.any { !it.name.startsWith(".") && it.name != "map.txt" } ?: false
            }
            newEntries[tag] = CachedPlatformEntry(dirMod, hasGames)
            platformResolver.resolvePlatform(tag, romsDir, if (hasGames) 1 else 0)
        }

        if (anyStale || platformCache == null) {
            scanCache.savePlatformCache(newEntries)
        }

        return all.groupBy { it.displayName }.map { (_, group) ->
            if (group.size == 1) group[0]
            else {
                val primary = group.maxBy { it.gameCount }
                primary.copy(
                    gameCount = group.sumOf { it.gameCount },
                    tags = group.map { it.tag }
                )
            }
        }.sortedNatural { it.displayName }
    }

    fun scanGames(tags: List<String>, subfolder: String? = null): List<Game> {
        if (tags.size == 1) return scanGames(tags[0], subfolder)
        val combined = tags.flatMap { scanGames(it, subfolder) }
        return combined.sortedWith(
            compareBy<Game> { !it.isSubfolder }
                .thenBy { !it.displayName.startsWith("★") }
                .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
        )
    }

    fun scanGames(tag: String, subfolder: String? = null): List<Game> {
        val baseDir = if (subfolder != null) {
            File(romsDir, "$tag/$subfolder")
        } else {
            File(romsDir, tag)
        }

        if (!baseDir.exists()) return emptyList()

        if (subfolder == null) {
            val dirMod = dirTimestamps[tag] ?: baseDir.lastModified()
            val artDirMod = File(artDir, tag).let { if (it.exists()) it.lastModified() else 0L }
            val cached = scanCache.loadGameCache(tag)
            if (cached != null && cached.dirLastModified == dirMod && cached.artDirLastModified == artDirMod) {
                dev.cannoli.scorza.util.DebugLog.write("scanGames($tag): cache hit, ${cached.games.size} games")
                return reconstructGames(tag, cached)
            }
            dev.cannoli.scorza.util.DebugLog.write("scanGames($tag): cache miss, doing full scan")
        }

        val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
        val coreName = platformResolver.getCoreName(tag)
        val appPackage = platformResolver.getAppPackage(tag)

        val files = baseDir.listFiles() ?: return emptyList()

        fun resolveTarget(isSubfolder: Boolean): LaunchTarget = when {
            isSubfolder -> LaunchTarget.RetroArch
            emuLaunch != null -> emuLaunch
            coreName != null -> LaunchTarget.RetroArch
            appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
            else -> LaunchTarget.RetroArch
        }

        val rawGames = files
            .filter { !it.name.startsWith(".") && it.name != "map.txt" }
            .mapNotNull { file ->
                if (file.isDirectory) {
                    val dirLaunch = findDirLaunchFile(file)
                    if (dirLaunch != null) {
                        Game(
                            file = dirLaunch.file,
                            displayName = file.name,
                            platformTag = tag,
                            artFile = findArt(tag, file.name),
                            launchTarget = resolveTarget(false),
                            discFiles = dirLaunch.discFiles
                        )
                    } else {
                        val hasChildren = file.listFiles()?.any { !it.name.startsWith(".") } == true
                        if (!hasChildren) return@mapNotNull null
                        Game(
                            file = file,
                            displayName = file.name,
                            platformTag = tag,
                            isSubfolder = true,
                            artFile = findArt(tag, file.name),
                            launchTarget = resolveTarget(true)
                        )
                    }
                } else {
                    Game(
                        file = file,
                        displayName = file.nameWithoutExtension,
                        platformTag = tag,
                        artFile = findArt(tag, file.nameWithoutExtension),
                        launchTarget = resolveTarget(false)
                    )
                }
            }

        val looseM3uNames = rawGames
            .filter { !it.isSubfolder && it.file.extension.equals("m3u", ignoreCase = true) }
            .map { it.file.nameWithoutExtension }
            .toSet()

        val (discCandidates, others) = rawGames.partition {
            !it.isSubfolder && discRegex.containsMatchIn(it.displayName)
        }

        val discGroups = discCandidates.groupBy { it.displayName.replace(discRegex, "").trim() }

        val usedM3uPaths = mutableSetOf<String>()
        val grouped = discGroups.flatMap { (baseName, games) ->
            if (games.size <= 1) return@flatMap games

            val existingM3u = others.find {
                it.file.extension.equals("m3u", ignoreCase = true) &&
                    it.file.nameWithoutExtension == baseName
            }
            if (existingM3u != null) {
                usedM3uPaths.add(existingM3u.file.absolutePath)
                return@flatMap listOf(existingM3u)
            }

            val sorted = games.sortedBy { it.file.name }
            listOf(sorted.first().copy(
                displayName = baseName,
                artFile = findArt(tag, baseName),
                discFiles = sorted.map { it.file }
            ))
        }

        val discFileSet = discGroups.values
            .filter { it.size > 1 }
            .flatten()
            .map { it.file.absolutePath }
            .toSet()
        val coveredByM3u = discGroups
            .filter { (baseName, games) ->
                games.size > 1 && looseM3uNames.contains(baseName)
            }
            .values.flatten().map { it.file.absolutePath }.toSet()

        val filtered = others.filter {
            it.file.absolutePath !in discFileSet &&
                it.file.absolutePath !in coveredByM3u &&
                it.file.absolutePath !in usedM3uPaths
        }

        val nameMap = parseMapFile(baseDir)
        val all = applyMap(stripTags(filtered + grouped), nameMap)

        if (subfolder == null) {
            val dirMod = dirTimestamps[tag] ?: baseDir.lastModified()
            val artDirMod = File(artDir, tag).let { if (it.exists()) it.lastModified() else 0L }
            scanCache.saveGameCache(tag, dirMod, artDirMod, all.map { game ->
                val artName = when {
                    game.artFile != null -> game.artFile.nameWithoutExtension
                    game.isSubfolder -> game.file.name
                    game.discFiles != null && !game.file.extension.equals("m3u", ignoreCase = true) ->
                        game.file.nameWithoutExtension.replace(discRegex, "").trim()
                    else -> game.file.nameWithoutExtension
                }
                CachedGameEntry(
                    path = game.file.absolutePath,
                    displayName = game.displayName,
                    artName = artName,
                    isSubfolder = game.isSubfolder,
                    discPaths = game.discFiles?.map { it.absolutePath } ?: emptyList()
                )
            })
        }

        return applyFavoritesAndSort(all)
    }

    private fun applyFavoritesAndSort(games: List<Game>): List<Game> {
        val favPaths = getFavoritePaths()
        val starred = games.map { game ->
            if (!game.isSubfolder && game.file.absolutePath in favPaths)
                game.copy(displayName = "★ ${game.displayName}")
            else game
        }
        return starred.sortedWith(
            compareBy<Game> { !it.isSubfolder }
                .thenBy { !it.displayName.startsWith("★") }
                .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
        )
    }

    private fun reconstructGames(tag: String, cached: CachedGameList): List<Game> {
        val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
        val coreName = platformResolver.getCoreName(tag)
        val appPackage = platformResolver.getAppPackage(tag)

        fun resolveTarget(isSubfolder: Boolean): LaunchTarget = when {
            isSubfolder -> LaunchTarget.RetroArch
            emuLaunch != null -> emuLaunch
            coreName != null -> LaunchTarget.RetroArch
            appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
            else -> LaunchTarget.RetroArch
        }

        val games = cached.games.map { entry ->
            Game(
                file = File(entry.path),
                displayName = entry.displayName,
                platformTag = tag,
                isSubfolder = entry.isSubfolder,
                artFile = findArt(tag, entry.artName),
                launchTarget = resolveTarget(entry.isSubfolder),
                discFiles = if (entry.discPaths.isNotEmpty()) entry.discPaths.map { File(it) } else null
            )
        }
        return applyFavoritesAndSort(games)
    }

    private data class DirLaunch(val file: File, val discFiles: List<File>? = null)

    private fun parseMapFile(dir: File): Map<String, String> {
        return mapCache.getOrPut(dir.absolutePath) {
            val mapFile = File(dir, "map.txt")
            if (!mapFile.exists()) return@getOrPut emptyMap()
            mapFile.readLines()
                .filter { '\t' in it }
                .associate { line ->
                    val (filename, displayName) = line.split('\t', limit = 2)
                    filename.trim() to displayName.trim()
                }
        }
    }

    private fun applyMap(games: List<Game>, nameMap: Map<String, String>): List<Game> {
        if (nameMap.isEmpty()) return games
        return games.map { game ->
            val mapped = nameMap[game.file.name]
            if (mapped != null) game.copy(displayName = mapped) else game
        }
    }

    private fun stripTags(games: List<Game>): List<Game> {
        val stripped = games.map { g ->
            if (g.isSubfolder) g to g.displayName
            else g to tagRegex.replace(g.displayName, "").trim()
        }
        val baseCounts = mutableMapOf<String, Int>()
        for ((_, base) in stripped) {
            baseCounts[base] = (baseCounts[base] ?: 0) + 1
        }
        return stripped.map { (game, base) ->
            if (game.isSubfolder || base.isEmpty()) game
            else if (baseCounts[base]!! > 1) game
            else game.copy(displayName = base)
        }
    }

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() }?.let { return DirLaunch(it) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() }?.let { return DirLaunch(it) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) }
            ?.let { return DirLaunch(it) }
        val children = dir.listFiles()?.filter { it.isFile } ?: return null
        val discFiles = children.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        if (discFiles.size > 1) {
            val sorted = discFiles.sortedBy { it.name }
            return DirLaunch(sorted.first(), sorted)
        }
        return null
    }

    @Volatile private var collectionsCache: List<Collection>? = null

    fun scanCollections(): List<Collection> {
        collectionsCache?.let { return it }
        if (!collectionsDir.exists()) return emptyList()
        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return emptyList()
        val result = files.map { file ->
            Collection(stem = file.nameWithoutExtension, file = file)
        }.sortedNatural { it.displayName }
        collectionsCache = result
        return result
    }

    private fun invalidateCollectionsCache() {
        collectionsCache = null
    }

    fun migrateCollectionsToHashedNames() {
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
            val lines = collectionParentsFile.readLines()
            val updated = lines.map { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || '=' !in trimmed) return@map line
                val (child, parent) = trimmed.split('=', limit = 2)
                val newChild = renameMap[child.trim()] ?: child.trim()
                val newParent = renameMap[parent.trim()] ?: parent.trim()
                "$newChild=$newParent"
            }
            collectionParentsFile.writeText(updated.joinToString("\n") + "\n")
        }

        val orderFile = File(configDir, "collection_order.txt")
        if (orderFile.exists()) {
            val lines = orderFile.readLines()
            val updated = lines.map { renameMap[it.trim()] ?: it.trim() }
            orderFile.writeText(updated.joinToString("\n") + "\n")
        }

        configDir.listFiles { f -> f.name.startsWith("child_order_") && f.extension == "txt" }
            ?.forEach { it.delete() }

        invalidateCollectionsCache()
        collectionParentsCache = null
        childrenByParentCache = null
    }

    fun scanCollectionGames(stem: String): List<Game> {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return emptyList()

        val lines = try {
            collFile.readLines()
        } catch (_: IOException) { return emptyList() }

        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.exists() && it.isFile }
            .map { file ->
                if (file.extension == "apk_launch") {
                    val pkg = try { file.readText().trim() } catch (_: IOException) { "" }
                    val tag = when {
                        file.absolutePath.startsWith(toolsDir.absolutePath + "/") -> "tools"
                        file.absolutePath.startsWith(portsDir.absolutePath + "/") -> "ports"
                        else -> file.parentFile?.name ?: ""
                    }
                    Game(
                        file = file,
                        displayName = file.nameWithoutExtension,
                        platformTag = tag,
                        launchTarget = if (pkg.isNotEmpty()) LaunchTarget.ApkLaunch(pkg) else LaunchTarget.RetroArch
                    )
                } else {
                    val tag = resolvePlatformTag(file)
                    val rawName = file.nameWithoutExtension
                    val displayName = rawName.replace(discRegex, "").trim().ifEmpty { rawName }
                    val artFile = findArt(tag, displayName)
                    val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
                    val coreName = platformResolver.getCoreName(tag)
                    val appPackage = platformResolver.getAppPackage(tag)

                    val target = when {
                        emuLaunch != null -> emuLaunch
                        coreName != null -> LaunchTarget.RetroArch
                        appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
                        else -> LaunchTarget.RetroArch
                    }

                    Game(
                        file = file,
                        displayName = displayName,
                        platformTag = tag,
                        artFile = artFile,
                        launchTarget = target
                    )
                }
            }
            .let(::stripTags)
            .map { game ->
                val mapped = game.file.parentFile?.let { parseMapFile(it) }?.get(game.file.name)
                if (mapped != null) game.copy(displayName = mapped) else game
            }
            .let { games ->
                if (stem.equals("Favorites", ignoreCase = true)) {
                    games.sortedWith(compareBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName })
                } else {
                    val favPaths = getFavoritePaths()
                    games.map { game ->
                        if (game.file.absolutePath in favPaths)
                            game.copy(displayName = "★ ${game.displayName}")
                        else game
                    }.sortedWith(
                        compareBy<Game> { !it.displayName.startsWith("★") }
                            .thenBy(dev.cannoli.scorza.util.NaturalSort) { it.displayName.removePrefix("★ ") }
                    )
                }
            }
    }

    fun addToCollection(stem: String, romPath: String) {
        collectionsDir.mkdirs()
        val collFile = File(collectionsDir, "$stem.txt")
        val existing = try {
            if (collFile.exists()) collFile.readLines().map { it.trim() } else emptyList()
        } catch (_: IOException) { emptyList() }
        if (romPath !in existing) {
            try { collFile.appendText("$romPath\n") } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    fun removeFromCollection(stem: String, romPath: String) {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return
        try {
            val remaining = collFile.readLines().map { it.trim() }.filter { it != romPath && it.isNotEmpty() }
            collFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        } catch (_: IOException) { }
        favoritesCache = null
    }

    fun isInCollection(stem: String, romPath: String): Boolean {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return false
        return try {
            collFile.readLines().any { it.trim() == romPath }
        } catch (_: IOException) { false }
    }

    fun getFavoritePaths(): Set<String> {
        favoritesCache?.let { return it }
        return synchronized(favoritesLock) {
            favoritesCache?.let { return it }
            val collFile = File(collectionsDir, "Favorites.txt")
            if (!collFile.exists()) return emptySet()
            val result = try {
                collFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } catch (_: IOException) { emptySet() }
            favoritesCache = result
            result
        }
    }

    fun createCollection(displayName: String): String {
        collectionsDir.mkdirs()
        val existingStems = collectionsDir.listFiles { f -> f.extension == "txt" }
            ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        val hash = Collection.generateUniqueHash(existingStems, displayName)
        val stem = "${displayName}_$hash"
        File(collectionsDir, "$stem.txt").createNewFile()
        invalidateCollectionsCache()
        return stem
    }

    fun deleteCollection(stem: String) {
        File(collectionsDir, "$stem.txt").delete()
        removeFromCollectionParents(stem)
        invalidateCollectionsCache()
    }

    fun renameCollection(oldStem: String, newDisplayName: String): Boolean {
        val oldFile = File(collectionsDir, "$oldStem.txt")
        if (!oldFile.exists()) return false
        val idx = oldStem.lastIndexOf('_')
        val hash = if (idx >= 0) oldStem.substring(idx + 1) else return false
        val newStem = "${newDisplayName}_$hash"
        val newFile = File(collectionsDir, "$newStem.txt")
        if (newFile.exists()) return false
        val renamed = oldFile.renameTo(newFile)
        if (renamed) {
            renameInCollectionParents(oldStem, newStem)
            invalidateCollectionsCache()
        }
        return renamed
    }

    fun getCollectionStems(): List<String> {
        return scanCollections().map { it.stem }
    }

    fun deleteGame(game: Game) {
        val paths = (game.discFiles?.map { it.absolutePath } ?: listOf(game.file.absolutePath)).toSet()
        if (game.discFiles != null) {
            game.discFiles.forEach { it.delete() }
        } else if (game.file.isDirectory) {
            game.file.deleteRecursively()
        } else {
            game.file.delete()
        }
        invalidateGameCacheForTag(game.platformTag)
        cleanCollectionPaths(paths)
    }

    private fun cleanCollectionPaths(deletedPaths: Set<String>) {
        if (!collectionsDir.exists()) return
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { collFile ->
            try {
                val lines = collFile.readLines()
                val cleaned = lines.filter { it.trim() !in deletedPaths }
                if (cleaned.size != lines.size) {
                    collFile.writeText(cleaned.joinToString("\n") + if (cleaned.isNotEmpty()) "\n" else "")
                }
            } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    fun scanApkLaunches(dir: File): List<LaunchTarget.ApkLaunch> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = try {
                file.readText().trim()
            } catch (_: IOException) { return@mapNotNull null }
            if (pkg.isNotEmpty()) LaunchTarget.ApkLaunch(pkg) else null
        }
    }

    fun scanTools(): List<Triple<File, String, LaunchTarget.ApkLaunch>> {
        return scanApkLaunchesWithNames(toolsDir)
    }

    fun scanPorts(): List<Triple<File, String, LaunchTarget.ApkLaunch>> {
        return scanApkLaunchesWithNames(portsDir)
    }

    val tools: File get() = toolsDir
    val ports: File get() = portsDir

    fun removeApkLaunch(type: String, displayName: String) {
        val dir = if (type == "tools") toolsDir else portsDir
        val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        File(dir, "$safeName.apk_launch").delete()
    }

    fun syncApkLaunches(dir: File, selected: List<Pair<String, String>>) {
        dir.mkdirs()
        dir.listFiles { f -> f.extension == "apk_launch" }?.forEach { it.delete() }
        selected.forEach { (displayName, packageName) ->
            val safeName = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            File(dir, "$safeName.apk_launch").writeText(packageName)
        }
    }

    fun ensureDirectories(onProgress: (() -> Unit)? = null) {
        listOf(
            romsDir, artDir, collectionsDir,
            File(cannoliRoot, "BIOS"),
            File(cannoliRoot, "Saves"),
            File(cannoliRoot, "Save States"),
            File(cannoliRoot, "Media/Screenshots"),
            File(cannoliRoot, "Media/Recordings"),
            File(cannoliRoot, "Config"),
            File(cannoliRoot, "Config/Overrides"),
            File(cannoliRoot, "Config/Overrides/Cores"),
            File(cannoliRoot, "Config/Overrides/systems"),
            File(cannoliRoot, "Config/Overrides/Games"),
            File(cannoliRoot, "Backup"),
            File(cannoliRoot, "Guides"),
            File(cannoliRoot, "Wallpapers"),
            toolsDir, portsDir
        ).forEach { it.mkdirs(); onProgress?.invoke() }

        for (tag in platformResolver.getAllTags()) {
            File(romsDir, tag).mkdirs(); onProgress?.invoke()
            File(artDir, tag).mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "BIOS/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Saves/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Save States/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Guides/$tag").mkdirs(); onProgress?.invoke()
        }
    }

    private fun scanApkLaunchesWithNames(dir: File): List<Triple<File, String, LaunchTarget.ApkLaunch>> {
        if (!dir.exists()) return emptyList()

        val files = dir.listFiles { f -> f.extension == "apk_launch" } ?: return emptyList()

        return files.mapNotNull { file ->
            val pkg = try {
                file.readText().trim()
            } catch (_: IOException) { return@mapNotNull null }
            if (pkg.isNotEmpty()) {
                Triple(file, file.nameWithoutExtension, LaunchTarget.ApkLaunch(pkg))
            } else null
        }.sortedNatural { it.second }
    }

    fun getCollectionsContaining(romPath: String): Set<String> {
        if (!collectionsDir.exists()) return emptySet()
        val result = mutableSetOf<String>()
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            try {
                if (file.readLines().any { it.trim() == romPath }) {
                    result.add(file.nameWithoutExtension)
                }
            } catch (_: IOException) { }
        }
        return result
    }

    private fun resolvePlatformTag(romFile: File): String {
        val romsPath = romsDir.absolutePath + "/"
        val filePath = romFile.absolutePath
        if (!filePath.startsWith(romsPath)) return romFile.parentFile?.name ?: ""
        val relative = filePath.removePrefix(romsPath)
        return relative.substringBefore('/')
    }

    private fun findArt(tag: String, gameName: String): File? {
        val lookup = artCache.getOrPut(tag) {
            val artTagDir = File(artDir, tag)
            if (!artTagDir.exists()) return@getOrPut emptyMap()
            val map = mutableMapOf<String, File>()
            artTagDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    map[file.nameWithoutExtension] = file
                }
            }
            map
        }
        return lookup[gameName]
    }

    fun invalidateFavorites() {
        favoritesCache = null
    }

    fun invalidateArtForTag(tag: String) {
        artCache.remove(tag)
    }

    fun invalidateMapForDir(path: String) {
        mapCache.remove(path)
    }

    fun invalidateGameCacheForTag(tag: String) {
        scanCache.invalidatePlatform(tag)
        dirTimestamps.remove(tag)
    }

    fun invalidateAllCaches() {
        artCache.clear()
        mapCache.clear()
        favoritesCache = null
        dirTimestamps.clear()
        scanCache.invalidateAll()
    }

    private val configDir = File(cannoliRoot, "Config")

    fun loadPlatformOrder(): List<String> {
        val file = File(configDir, "platform_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePlatformOrder(tags: List<String>) {
        configDir.mkdirs()
        File(configDir, "platform_order.txt").writeText(tags.joinToString("\n") + "\n")
    }

    fun getRaGameId(romPath: String): Int? {
        val file = File(configDir, "ra_game_ids.txt")
        if (!file.exists()) return null
        return try {
            file.readLines().firstOrNull { it.startsWith("$romPath=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        } catch (_: IOException) { null }
    }

    fun setRaGameId(romPath: String, gameId: Int?) {
        configDir.mkdirs()
        val file = File(configDir, "ra_game_ids.txt")
        val existing = try {
            if (file.exists()) file.readLines().filter { !it.startsWith("$romPath=") && it.isNotEmpty() }
            else emptyList()
        } catch (_: IOException) { emptyList() }
        val lines = if (gameId != null) existing + "$romPath=$gameId" else existing
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    fun loadCollectionOrder(): List<String> {
        val file = File(configDir, "collection_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveCollectionOrder(names: List<String>) {
        configDir.mkdirs()
        File(configDir, "collection_order.txt").writeText(names.joinToString("\n") + "\n")
    }

    fun loadToolOrder(): List<String> {
        val file = File(configDir, "tool_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveToolOrder(names: List<String>) {
        configDir.mkdirs()
        File(configDir, "tool_order.txt").writeText(names.joinToString("\n") + "\n")
    }

    fun loadPortOrder(): List<String> {
        val file = File(configDir, "port_order.txt")
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePortOrder(names: List<String>) {
        configDir.mkdirs()
        File(configDir, "port_order.txt").writeText(names.joinToString("\n") + "\n")
    }

    private val collectionParentsFile = File(configDir, "collection_parents.txt")
    @Volatile private var collectionParentsCache: Map<String, String>? = null
    @Volatile private var childrenByParentCache: Map<String, List<String>>? = null

    fun loadCollectionParents(): Map<String, String> {
        collectionParentsCache?.let { return it }
        if (!collectionParentsFile.exists()) return emptyMap()
        return try {
            val pairs = collectionParentsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && '=' in it }
                .map { line ->
                    val (child, parent) = line.split('=', limit = 2)
                    child.trim() to parent.trim()
                }
                .filter { it.second.isNotEmpty() }
            val map = linkedMapOf<String, String>()
            pairs.forEach { (child, parent) -> map[child] = parent }
            map as Map<String, String>
        } catch (_: IOException) { emptyMap<String, String>() }
            .also {
                collectionParentsCache = it
                childrenByParentCache = buildChildrenIndex(it)
            }
    }

    private fun buildChildrenIndex(parents: Map<String, String>): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        for ((child, parent) in parents) {
            index.getOrPut(parent) { mutableListOf() }.add(child)
        }
        return index
    }

    private fun saveCollectionParents(map: Map<String, String>) {
        configDir.mkdirs()
        val filtered = map.filterValues { it.isNotEmpty() }
        collectionParentsCache = null
        childrenByParentCache = null
        if (filtered.isEmpty()) {
            collectionParentsFile.delete()
            return
        }
        collectionParentsFile.writeText(
            filtered.entries.joinToString("\n") { (child, parent) ->
                "$child=$parent"
            } + "\n"
        )
    }

    fun getCollectionParent(childStem: String): String? {
        return loadCollectionParents()[childStem]
    }

    fun setCollectionParent(childStem: String, parentStem: String?) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        if (parentStem == null) map.remove(childStem) else map[childStem] = parentStem
        saveCollectionParents(map)
    }

    fun getChildCollections(parentStem: String): List<String> {
        loadCollectionParents()
        return childrenByParentCache?.get(parentStem) ?: emptyList()
    }

    fun reorderChildren(parentStem: String, orderedChildStems: List<String>) {
        val map = loadCollectionParents()
        val entries = map.entries.toMutableList()
        val firstChildIdx = entries.indexOfFirst { it.value == parentStem }
        entries.removeAll { it.value == parentStem }
        val insertIdx = if (firstChildIdx >= 0) firstChildIdx.coerceAtMost(entries.size) else entries.size
        orderedChildStems.forEachIndexed { i, stem ->
            entries.add(insertIdx + i, java.util.AbstractMap.SimpleEntry(stem, parentStem))
        }
        val newMap = linkedMapOf<String, String>()
        entries.forEach { newMap[it.key] = it.value }
        saveCollectionParents(newMap)
    }

    fun isTopLevelCollection(stem: String): Boolean {
        return getCollectionParent(stem) == null
    }

    fun getDescendants(stem: String): Set<String> {
        loadCollectionParents()
        val index = childrenByParentCache ?: return emptySet()
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(stem)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in index[current] ?: emptyList()) {
                if (result.add(child)) queue.add(child)
            }
        }
        return result
    }

    fun getAncestors(stem: String): Set<String> {
        val allParents = loadCollectionParents()
        val result = mutableSetOf<String>()
        var current = stem
        while (true) {
            val parent = allParents[current] ?: break
            if (!result.add(parent)) break
            current = parent
        }
        return result
    }

    fun setChildCollections(parentStem: String, children: Set<String>) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        val currentChildren = map.entries
            .filter { it.value == parentStem }
            .map { it.key }
            .toSet()
        for (removed in currentChildren - children) {
            map.remove(removed)
        }
        for (added in children - currentChildren) {
            map[added] = parentStem
        }
        saveCollectionParents(map)
    }

    private fun removeFromCollectionParents(stem: String) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        map.remove(stem)
        map.entries.removeAll { it.value == stem }
        saveCollectionParents(map)
    }

    private fun renameInCollectionParents(oldStem: String, newStem: String) {
        val map = loadCollectionParents()
        val updated = linkedMapOf<String, String>()
        for ((child, parent) in map) {
            val newChild = if (child == oldStem) newStem else child
            val newParent = if (parent == oldStem) newStem else parent
            updated[newChild] = newParent
        }
        saveCollectionParents(updated)
    }
}
