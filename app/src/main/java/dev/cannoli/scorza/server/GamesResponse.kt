package dev.cannoli.scorza.server

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.util.RomDirectoryWalker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

object GamesResponse {

    private val SAVE_EXTENSIONS = setOf("srm", "sav", "fla", "rtc", "mcr", "mcd", "psm", "eep")

    @Serializable
    data class GameJson(
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
    ): String {
        val games = roms.allRomsForPlatform(platformTag).map { gameJson(it, cannoliRoot, romsRoot, platformTag, walker) }
        val folders = walker?.categoryFolders(platformTag) ?: emptyList()
        return serverJson.encodeToString(
            GamesListResponse.serializer(),
            GamesListResponse(platformTag, platformDisplayName, games, folders),
        )
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
        val game = gameJson(rom, cannoliRoot, romsRoot, platformTag, walker)
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
                size = game.size,
                modified = game.modified,
                hasArt = game.hasArt,
                artUrl = game.artUrl,
                savesCount = game.savesCount,
                statesCount = game.statesCount,
                guidesCount = game.guidesCount,
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
    ): GameJson {
        val romFile = rom.path
        val size = try { if (romFile.exists()) romFile.length() else 0L } catch (_: Throwable) { 0L }
        val modified = try { if (romFile.exists()) romFile.lastModified() else 0L } catch (_: Throwable) { 0L }
        val baseName = romFile.nameWithoutExtension
        val relativeRomPath = romFile.absolutePath.removePrefix("${romsRoot.absolutePath}${File.separator}")
        val artFile = resolveArtFile(cannoliRoot, platformTag, baseName)
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
            "/files/art/${rel.replace(File.separatorChar, '/')}"
        }
        return GameJson(
            id = rom.id,
            rom = romFile.name,
            displayName = rom.displayName,
            sortKey = rom.displayName.lowercase(),
            path = relativeRomPath.replace(File.separatorChar, '/'),
            folder = folderStr,
            size = size,
            modified = modified,
            hasArt = artFile != null,
            artUrl = artUrl,
            savesCount = countSaves(cannoliRoot, platformTag, baseName),
            statesCount = countStates(cannoliRoot, platformTag, baseName),
            guidesCount = countGuides(cannoliRoot, platformTag, baseName),
            raGameId = rom.raGameId,
            lastPlayedAt = rom.lastPlayedAt,
            multiDisc = rom.isMultiDisc,
        )
    }

    internal fun resolveArtFile(cannoliRoot: File, platformTag: String, baseName: String): File? {
        val dir = File(cannoliRoot, "Art/$platformTag")
        if (!dir.isDirectory) return null
        val base = java.text.Normalizer.normalize(baseName, java.text.Normalizer.Form.NFC)
        return try {
            dir.listFiles { f ->
                f.isFile && java.text.Normalizer
                    .normalize(f.nameWithoutExtension, java.text.Normalizer.Form.NFC)
                    .equals(base, ignoreCase = true)
            }?.minByOrNull { it.name.lowercase() }
        } catch (_: Throwable) { null }
    }

    private fun countSaves(cannoliRoot: File, platformTag: String, baseName: String): Int {
        val dir = File(cannoliRoot, "Saves/$platformTag")
        if (!dir.isDirectory) return 0
        return try {
            dir.listFiles { f ->
                f.isFile &&
                    f.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                    f.extension.lowercase() in SAVE_EXTENSIONS
            }?.size ?: 0
        } catch (_: Throwable) { 0 }
    }

    private fun countStates(cannoliRoot: File, platformTag: String, baseName: String): Int {
        val dir = File(cannoliRoot, "Save States/$platformTag/$baseName")
        if (!dir.isDirectory) return 0
        return try {
            (0..10).count { slot ->
                val name = when (slot) {
                    0 -> "$baseName.state.auto"
                    1 -> "$baseName.state"
                    else -> "$baseName.state${slot - 1}"
                }
                File(dir, name).isFile
            }
        } catch (_: Throwable) { 0 }
    }

    private fun countGuides(cannoliRoot: File, platformTag: String, baseName: String): Int {
        val dir = File(cannoliRoot, "Guides/$platformTag/$baseName")
        if (!dir.isDirectory) return 0
        return try {
            dir.listFiles { f -> f.isFile }?.size ?: 0
        } catch (_: Throwable) { 0 }
    }
}
