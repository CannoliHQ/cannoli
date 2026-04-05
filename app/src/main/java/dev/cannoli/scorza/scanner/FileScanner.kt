package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.sortedNatural
import java.io.File
import java.io.IOException

class FileScanner(
    private val cannoliRoot: File,
    private val platformResolver: PlatformResolver,
    private val collectionManager: CollectionManager,
    romsDir: File? = null
) {
    private val romsDir = romsDir ?: File(cannoliRoot, "Roms")
    private val artDir = File(cannoliRoot, "Art")
    private val collectionsDir = File(cannoliRoot, "Collections")
    private val toolsDir = File(cannoliRoot, "Config/Launch Scripts/Tools")
    private val portsDir = File(cannoliRoot, "Config/Launch Scripts/Ports")

    private val artCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, File>>()
    private val mapCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    private val scanCache = ScanCache(cannoliRoot)
    val dirTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val defaultIgnoreExtensions = setOf("srm", "sav")
    private val ignoreExtensionsFile = File(cannoliRoot, "Config/ignore_extensions_roms.txt")
    @Volatile private var ignoreExtensions: Set<String> = defaultIgnoreExtensions

    fun loadIgnoreExtensions() {
        if (!ignoreExtensionsFile.exists()) {
            ignoreExtensionsFile.parentFile?.mkdirs()
            ignoreExtensionsFile.writeText(defaultIgnoreExtensions.joinToString("\n") { ".$it" } + "\n")
        }
        ignoreExtensions = ignoreExtensionsFile.readLines()
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun isIgnoredExtension(file: File): Boolean =
        file.extension.lowercase() in ignoreExtensions

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
                files?.any { !it.name.startsWith(".") && it.name != "map.txt" && !isIgnoredExtension(it) } ?: false
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

        val files = baseDir.listFiles() ?: return emptyList()

        val rawGames = files
            .filter { !it.name.startsWith(".") && it.name != "map.txt" && !isIgnoredExtension(it) }
            .mapNotNull { file ->
                if (file.isDirectory) {
                    val dirLaunch = findDirLaunchFile(file)
                    if (dirLaunch != null) {
                        Game(
                            file = dirLaunch.file,
                            displayName = file.name,
                            platformTag = tag,
                            artFile = findArt(tag, file.name),
                            launchTarget = resolveTarget(tag, false),
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
                            launchTarget = resolveTarget(tag, true)
                        )
                    }
                } else {
                    Game(
                        file = file,
                        displayName = file.nameWithoutExtension,
                        platformTag = tag,
                        artFile = findArt(tag, file.nameWithoutExtension),
                        launchTarget = resolveTarget(tag, false)
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
        val favPaths = collectionManager.getFavoritePaths()
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

    private fun resolveTarget(tag: String, isSubfolder: Boolean): LaunchTarget {
        val emuLaunch = platformResolver.getEmuLaunch(tag, romsDir)
        val coreName = platformResolver.getCoreName(tag)
        val appPackage = platformResolver.getAppPackage(tag)
        return when {
            isSubfolder -> LaunchTarget.RetroArch
            emuLaunch != null -> emuLaunch
            coreName != null -> LaunchTarget.RetroArch
            appPackage != null -> LaunchTarget.ApkLaunch(appPackage)
            else -> LaunchTarget.RetroArch
        }
    }

    private fun reconstructGames(tag: String, cached: CachedGameList): List<Game> {
        val games = cached.games.mapNotNull { entry ->
            val file = File(entry.path)
            if (!entry.isSubfolder && isIgnoredExtension(file)) return@mapNotNull null
            Game(
                file = file,
                displayName = entry.displayName,
                platformTag = tag,
                isSubfolder = entry.isSubfolder,
                artFile = findArt(tag, entry.artName),
                launchTarget = resolveTarget(tag, entry.isSubfolder),
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

                    Game(
                        file = file,
                        displayName = displayName,
                        platformTag = tag,
                        artFile = artFile,
                        launchTarget = resolveTarget(tag, false)
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
                    games
                } else {
                    val favPaths = collectionManager.getFavoritePaths()
                    val tagged = games.map { game ->
                        if (game.file.absolutePath in favPaths)
                            game.copy(displayName = "★ ${game.displayName}")
                        else game
                    }
                    val favs = tagged.filter { it.displayName.startsWith("★") }
                    val rest = tagged.filter { !it.displayName.startsWith("★") }
                    favs + rest
                }
            }
    }

    fun resolveGameFromPath(path: String): Game? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        val game = if (file.extension == "apk_launch") {
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
            Game(
                file = file,
                displayName = displayName,
                platformTag = tag,
                artFile = artFile,
                launchTarget = resolveTarget(tag, false)
            )
        }
        val mapped = game.file.parentFile?.let { parseMapFile(it) }?.get(game.file.name)
        return if (mapped != null) game.copy(displayName = mapped) else game
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
        collectionManager.cleanCollectionPaths(paths)
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
            File(cannoliRoot, "Config/Ordering"),
            File(cannoliRoot, "Config/State"),
            File(cannoliRoot, "Config/RetroArch"),
            File(cannoliRoot, "Config/Cache"),
            File(cannoliRoot, "Config/Overrides"),
            File(cannoliRoot, "Config/Overrides/Cores"),
            File(cannoliRoot, "Config/Overrides/systems"),
            File(cannoliRoot, "Config/Overrides/Games"),
            File(cannoliRoot, "Backup"),
            File(cannoliRoot, "Guides"),
            File(cannoliRoot, "Wallpapers"),
            toolsDir, portsDir
        ).forEach { it.mkdirs(); onProgress?.invoke() }

        migrateConfigLayout()

        for (tag in platformResolver.getAllTags()) {
            File(romsDir, tag).mkdirs(); onProgress?.invoke()
            File(artDir, tag).mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "BIOS/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Saves/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Save States/$tag").mkdirs(); onProgress?.invoke()
            File(cannoliRoot, "Guides/$tag").mkdirs(); onProgress?.invoke()
        }
    }

    private fun migrateConfigLayout() {
        fun move(old: File, new: File) {
            if (old.exists() && !new.exists()) old.renameTo(new)
        }
        val cfg = File(cannoliRoot, "Config")
        val ordering = File(cfg, "Ordering")
        val state = File(cfg, "State")
        val ra = File(cfg, "RetroArch")
        val cache = File(cfg, "Cache")

        move(File(cfg, "platform_order.txt"), File(ordering, "platform_order.txt"))
        move(File(cfg, "collection_order.txt"), File(ordering, "collection_order.txt"))
        move(File(cfg, "collection_parents.txt"), File(ordering, "collection_parents.txt"))
        move(File(cfg, "tool_order.txt"), File(ordering, "tool_order.txt"))
        move(File(cfg, "port_order.txt"), File(ordering, "port_order.txt"))
        cfg.listFiles { f -> f.name.startsWith("child_order_") && f.extension == "txt" }
            ?.forEach { move(it, File(ordering, it.name)) }

        move(File(cfg, "recently_played.txt"), File(state, "recently_played.txt"))
        move(File(cfg, "quick_resume.txt"), File(state, "quick_resume.txt"))
        move(File(cfg, "guide_positions.ini"), File(state, "guide_positions.ini"))

        move(File(cfg, "retroarch.cfg"), File(ra, "retroarch.cfg"))
        move(File(cfg, "retroarch_launch.cfg"), File(ra, "retroarch_launch.cfg"))
        move(File(cfg, "ra_game_ids.txt"), File(ra, "ra_game_ids.txt"))

        move(File(cfg, ".ra_config_hash"), File(ra, ".ra_config_hash"))

        move(File(cfg, ".platform_cache.json"), File(cache, ".platform_cache.json"))
        move(File(cfg, ".game_cache"), File(cache, ".game_cache"))
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
        dirTimestamps.clear()
        scanCache.invalidateAll()
    }

    private val configDir = File(cannoliRoot, "Config")
    private val raDir = File(configDir, "RetroArch")

    fun getRaGameId(romPath: String): Int? {
        val file = File(raDir, "ra_game_ids.txt")
        if (!file.exists()) return null
        return try {
            file.readLines().firstOrNull { it.startsWith("$romPath=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        } catch (_: IOException) { null }
    }

    fun setRaGameId(romPath: String, gameId: Int?) {
        raDir.mkdirs()
        val file = File(raDir, "ra_game_ids.txt")
        val existing = try {
            if (file.exists()) file.readLines().filter { !it.startsWith("$romPath=") && it.isNotEmpty() }
            else emptyList()
        } catch (_: IOException) { emptyList() }
        val lines = if (gameId != null) existing + "$romPath=$gameId" else existing
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }
}
