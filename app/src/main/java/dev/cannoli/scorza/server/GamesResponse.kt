package dev.cannoli.scorza.server

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.RomDirectoryWalker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

object GamesResponse {

    private val SAVE_EXTENSIONS = setOf("srm", "sav", "fla", "rtc", "mcr", "mcd", "psm", "eep")

    // Checking costs a short blocking socket read, so it is worth doing per batch, not per game.
    private const val CANCEL_CHECK_INTERVAL = 128

    // One entry per art directory, replaced whenever that directory's timestamp moves.
    private val artIndexCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<String, File>>>()

    @Serializable
    data class GameJson(
        val id: Long,
        val rom: String,
        val displayName: String,
        val sortKey: String,
        val path: String,
        val folder: String,
        val hasArt: Boolean,
        val artUrl: String? = null,
        val savesCount: Int,
        val statesCount: Int,
        val guidesCount: Int,
        val cheatsCount: Int,
        val raGameId: Int? = null,
        val lastPlayedAt: Long? = null,
        val multiDisc: Boolean,
    )

    @Serializable
    data class GamesListResponse(
        val platform: String,
        val displayName: String,
        val games: List<GameJson>,
        val folders: List<String>,
    )

    @Serializable
    data class GameDetailResponse(
        val platform: String,
        @SerialName("platformDisplayName") val platformDisplayName: String,
        val id: Long,
        val rom: String,
        val displayName: String,
        val sortKey: String,
        val path: String,
        val folder: String,
        val size: Long,
        val modified: Long,
        val hasArt: Boolean,
        val artUrl: String? = null,
        val savesCount: Int,
        val statesCount: Int,
        val guidesCount: Int,
        val cheatsCount: Int,
        val raGameId: Int? = null,
        val lastPlayedAt: Long? = null,
        val multiDisc: Boolean,
    )

    fun buildList(
        roms: RomsRepository,
        cannoliRoot: File,
        romsRoot: File,
        platformTag: String,
        platformDisplayName: String,
        walker: RomDirectoryWalker? = null,
        isArcade: Boolean = false,
        listDir: (File) -> Array<File>? = { it.listFiles() },
        isCancelled: () -> Boolean = { false },
    ): String {
        val index = PlatformIndex(cannoliRoot, platformTag, listDir)
        val startedQuery = System.currentTimeMillis()
        val all = roms.allRomsForPlatform(platformTag)
        val startedBuild = System.currentTimeMillis()
        val games = ArrayList<GameJson>(all.size)
        all.forEachIndexed { i, rom ->
            if (i > 0 && i % CANCEL_CHECK_INTERVAL == 0 && isCancelled()) throw RequestAbandonedException()
            games.add(gameJson(rom, cannoliRoot, romsRoot, platformTag, walker, index, listDir))
        }
        val startedFolders = System.currentTimeMillis()
        val folders = walker?.categoryFolders(platformTag, isArcade) ?: emptyList()
        val startedEncode = System.currentTimeMillis()
        val json = serverJson.encodeToString(
            GamesListResponse.serializer(),
            GamesListResponse(platformTag, platformDisplayName, games, folders),
        )
        dev.cannoli.scorza.util.KitchenLog.log(
            "buildList $platformTag n=${all.size} query=${startedBuild - startedQuery}ms " +
                "rows=${startedFolders - startedBuild}ms folders=${startedEncode - startedFolders}ms " +
                "encode=${System.currentTimeMillis() - startedEncode}ms"
        )
        return json
    }

    fun buildOne(
        roms: RomsRepository,
        cannoliRoot: File,
        romsRoot: File,
        platformTag: String,
        platformDisplayName: String,
        romId: Long,
        walker: RomDirectoryWalker? = null,
    ): String? {
        val rom = roms.gameById(romId) ?: return null
        if (!rom.platformTag.equals(platformTag, ignoreCase = true)) return null
        val listDir: (File) -> Array<File>? = { it.listFiles() }
        val game = gameJson(
            rom, cannoliRoot, romsRoot, platformTag, walker,
            PlatformIndex(cannoliRoot, platformTag, listDir), listDir,
        )
        return serverJson.encodeToString(
            GameDetailResponse.serializer(),
            GameDetailResponse(
                platform = platformTag,
                platformDisplayName = platformDisplayName,
                id = game.id,
                rom = game.rom,
                displayName = game.displayName,
                sortKey = game.sortKey,
                path = game.path,
                folder = game.folder,
                // Stat'd here rather than in GameJson: the list drops these two fields because
                // nothing renders them and they cost a syscall per game, but a single game detail
                // does show them and pays for exactly one file.
                size = try { rom.path.length() } catch (_: Throwable) { 0L },
                modified = try { rom.path.lastModified() } catch (_: Throwable) { 0L },
                hasArt = game.hasArt,
                artUrl = game.artUrl,
                savesCount = game.savesCount,
                statesCount = game.statesCount,
                guidesCount = game.guidesCount,
                cheatsCount = game.cheatsCount,
                raGameId = game.raGameId,
                lastPlayedAt = game.lastPlayedAt,
                multiDisc = game.multiDisc,
            ),
        )
    }

    private fun gameJson(
        rom: Rom,
        cannoliRoot: File,
        romsRoot: File,
        platformTag: String,
        walker: RomDirectoryWalker?,
        index: PlatformIndex,
        listDir: (File) -> Array<File>?,
    ): GameJson {
        val romFile = rom.path
        val baseName = romFile.nameWithoutExtension
        val relativeRomPath = romFile.absolutePath.removePrefix("${romsRoot.absolutePath}${File.separator}")
        val artFile = index.artFor(baseName)
        val folderStr = if (walker == null) {
            ""
        } else {
            val gameUnit = walker.gameDirectory(romFile) ?: romFile
            val platformDir = File(romsRoot, platformTag)
            val unitParent = gameUnit.parentFile
            if (unitParent == null || unitParent == platformDir) {
                ""
            } else {
                unitParent.absolutePath
                    .removePrefix("${platformDir.absolutePath}${File.separator}")
                    .replace(File.separatorChar, '/')
            }
        }
        val artUrl = artFile?.let {
            val rel = it.absolutePath.removePrefix("${File(cannoliRoot, "Art").absolutePath}${File.separator}")
            val encoded = rel.split(File.separatorChar).joinToString("/", transform = ::encodePathSegment)
            "/api/art/$encoded?v=${index.artVersion}"
        }
        return GameJson(
            id = rom.id,
            rom = romFile.name,
            displayName = rom.displayName,
            sortKey = rom.displayName.lowercase(),
            path = relativeRomPath.replace(File.separatorChar, '/'),
            folder = folderStr,
            hasArt = artFile != null,
            artUrl = artUrl,
            savesCount = index.savesCountFor(baseName),
            statesCount = countStates(index, baseName),
            guidesCount = countGuides(index, baseName, listDir),
            cheatsCount = countCheats(index, baseName, listDir),
            raGameId = rom.raGameId,
            lastPlayedAt = rom.lastPlayedAt,
            multiDisc = rom.isMultiDisc,
        )
    }

    // URLEncoder targets form encoding, so its "+" for space has to become %20 to survive as a path.
    private fun encodePathSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Art and save files for one platform, listed once and keyed by ROM base name. Resolving these
     *  per game instead costs a full directory listing per game, which is quadratic over the library
     *  and unusable on a FUSE-mounted SD card. */
    private class PlatformIndex(
        cannoliRoot: File,
        platformTag: String,
        listDir: (File) -> Array<File>?,
    ) {
        private val artDir = File(cannoliRoot, "Art/$platformTag")

        /** Cache-busting token for every art url on this platform. Taken from the directory rather
         *  than each file, which would cost a stat per game; adding, replacing or removing art all
         *  touch the directory. Coarser than per-file, so one art change re-fetches the platform's
         *  covers, which is a fair trade for keeping the list request free of extra syscalls. */
        val artVersion: String by lazy {
            (if (artDir.isDirectory) artDir.lastModified() else 0L).toString(36)
        }

        // Building this means a stat per art file to tell files from directories, which on a large
        // platform costs as much as everything else in the request put together. It is cached
        // against the directory's timestamp, so only the first request after a change pays.
        private val art: Map<String, File> by lazy {
            val stamp = artVersion
            artIndexCache[artDir.absolutePath]?.let { if (it.first == stamp) return@lazy it.second }
            val built = index(artDir, listDir) {
                it.filter { f -> f.isFile }
                    .groupBy { f -> artKey(f.nameWithoutExtension) }
                    .mapValues { (_, files) -> files.minByOrNull { f -> f.name.lowercase() }!! }
            }
            artIndexCache[artDir.absolutePath] = stamp to built
            built
        }

        private val saves: Map<String, Int> by lazy {
            index(File(cannoliRoot, "Saves/$platformTag"), listDir) {
                it.filter { f -> f.isFile && f.extension.lowercase() in SAVE_EXTENSIONS }
                    .groupingBy { f -> f.nameWithoutExtension.lowercase() }
                    .eachCount()
            }
        }

        // Save States, Guides and Cheats each hold one directory per game. Listing that parent once
        // replaces an isDirectory stat per game, and only games that actually have a directory pay
        // to look inside it.
        private val stateDirs: Map<String, File> by lazy {
            gameDirs(File(cannoliRoot, "Save States/$platformTag"), listDir)
        }

        private val guideDirs: Map<String, File> by lazy {
            gameDirs(File(cannoliRoot, "Guides/$platformTag"), listDir)
        }

        private val cheatDirs: Map<String, File> by lazy {
            gameDirs(File(cannoliRoot, "Cheats/$platformTag"), listDir)
        }

        fun artFor(baseName: String): File? = art[artKey(baseName)]

        fun savesCountFor(baseName: String): Int = saves[baseName.lowercase()] ?: 0

        fun stateDirFor(baseName: String): File? = stateDirs[baseName.lowercase()]

        fun guideDirFor(baseName: String): File? = guideDirs[baseName.lowercase()]

        fun cheatDirFor(baseName: String): File? = cheatDirs[baseName.lowercase()]

        private fun gameDirs(dir: File, listDir: (File) -> Array<File>?): Map<String, File> =
            index(dir, listDir) {
                it.filter { f -> f.isDirectory }.associateBy { f -> f.name.lowercase() }
            }

        private fun <T> index(
            dir: File,
            listDir: (File) -> Array<File>?,
            build: (List<File>) -> Map<String, T>,
        ): Map<String, T> {
            if (!dir.isDirectory) return emptyMap()
            return try {
                build(listDir(dir)?.asList() ?: emptyList())
            } catch (_: Throwable) { emptyMap() }
        }

        private fun artKey(baseName: String): String =
            java.text.Normalizer.normalize(baseName, java.text.Normalizer.Form.NFC).lowercase()
    }

    internal fun resolveArtFile(
        cannoliRoot: File,
        platformTag: String,
        baseName: String,
        listDir: (File) -> Array<File>? = { it.listFiles() },
    ): File? = PlatformIndex(cannoliRoot, platformTag, listDir).artFor(baseName)

    private fun countStates(index: PlatformIndex, baseName: String): Int {
        val dir = index.stateDirFor(baseName) ?: return 0
        return try {
            (0..10).count { slot -> File(dir, raStateName(baseName, slot)).isFile }
        } catch (_: Throwable) { 0 }
    }

    private fun countGuides(
        index: PlatformIndex,
        baseName: String,
        listDir: (File) -> Array<File>?,
    ): Int {
        val dir = index.guideDirFor(baseName) ?: return 0
        return try {
            listDir(dir)?.count { f -> f.isFile } ?: 0
        } catch (_: Throwable) { 0 }
    }

    private fun countCheats(
        index: PlatformIndex,
        baseName: String,
        listDir: (File) -> Array<File>?,
    ): Int {
        val dir = index.cheatDirFor(baseName) ?: return 0
        return try {
            listDir(dir)?.count { f -> f.isFile && f.extension.equals("cht", ignoreCase = true) } ?: 0
        } catch (_: Throwable) { 0 }
    }
}
