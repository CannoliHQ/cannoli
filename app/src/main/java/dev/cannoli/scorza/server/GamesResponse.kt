package dev.cannoli.scorza.server

import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import java.io.File

object GamesResponse {

    private val SAVE_EXTENSIONS = setOf("srm", "sav", "fla", "rtc", "mcr", "mcd", "psm", "eep")

    fun buildList(
        roms: RomsRepository,
        cannoliRoot: File,
        platformTag: String,
        platformDisplayName: String,
    ): String {
        val items = roms.gamesForPlatform(platformTag, null)
        val games = items.filterIsInstance<ListItem.RomItem>().map { it.rom }
        val sb = StringBuilder()
        sb.append("{\"platform\":\"").append(escapeJson(platformTag)).append("\",")
        sb.append("\"displayName\":\"").append(escapeJson(platformDisplayName)).append("\",")
        sb.append("\"games\":[")
        games.forEachIndexed { idx, rom ->
            if (idx > 0) sb.append(',')
            sb.append(gameToJson(rom, cannoliRoot, platformTag))
        }
        sb.append("]}")
        return sb.toString()
    }

    fun buildOne(
        roms: RomsRepository,
        cannoliRoot: File,
        platformTag: String,
        platformDisplayName: String,
        romId: Long,
    ): String? {
        val rom = roms.gameById(romId) ?: return null
        if (!rom.platformTag.equals(platformTag, ignoreCase = true)) return null
        val gameJson = gameToJson(rom, cannoliRoot, platformTag)
        val inner = gameJson.removePrefix("{").removeSuffix("}")
        val prefix = "{\"platform\":\"${escapeJson(platformTag)}\"," +
            "\"platformDisplayName\":\"${escapeJson(platformDisplayName)}\""
        return if (inner.isEmpty()) "$prefix}" else "$prefix,$inner}"
    }

    private fun gameToJson(rom: Rom, cannoliRoot: File, platformTag: String): String {
        val romFile = rom.path
        val size = try { if (romFile.exists()) romFile.length() else 0L } catch (_: Throwable) { 0L }
        val modified = try { if (romFile.exists()) romFile.lastModified() else 0L } catch (_: Throwable) { 0L }
        val baseName = romFile.nameWithoutExtension
        val savesCount = countSaves(cannoliRoot, platformTag, baseName)
        val statesCount = countStates(cannoliRoot, platformTag, baseName)
        val guidesCount = countGuides(cannoliRoot, platformTag, baseName)
        val relativeRomPath = romFile.absolutePath.removePrefix("${File(cannoliRoot, "Roms").absolutePath}${File.separator}")
        val artFile = resolveArtFile(cannoliRoot, platformTag, baseName)
        val hasArt = artFile != null
        val artUrl = artFile?.let {
            val rel = it.absolutePath.removePrefix("${File(cannoliRoot, "Art").absolutePath}${File.separator}")
            "/files/art/${rel.replace(File.separatorChar, '/')}"
        }
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(rom.id).append(',')
        sb.append("\"rom\":\"").append(escapeJson(romFile.name)).append("\",")
        sb.append("\"displayName\":\"").append(escapeJson(rom.displayName)).append("\",")
        sb.append("\"sortKey\":\"").append(escapeJson(rom.displayName.lowercase())).append("\",")
        sb.append("\"path\":\"").append(escapeJson(relativeRomPath.replace(File.separatorChar, '/'))).append("\",")
        sb.append("\"size\":").append(size).append(',')
        sb.append("\"modified\":").append(modified).append(',')
        sb.append("\"hasArt\":").append(hasArt)
        if (artUrl != null) sb.append(',').append("\"artUrl\":\"").append(escapeJson(artUrl)).append('"')
        sb.append(',').append("\"savesCount\":").append(savesCount)
        sb.append(',').append("\"statesCount\":").append(statesCount)
        sb.append(',').append("\"guidesCount\":").append(guidesCount)
        sb.append(',').append("\"raGameId\":").append(rom.raGameId ?: "null")
        sb.append(',').append("\"lastPlayedAt\":").append(rom.lastPlayedAt ?: "null")
        sb.append(',').append("\"discPaths\":").append(
            if (rom.discFiles.isNullOrEmpty()) "null"
            else rom.discFiles!!.joinToString(prefix = "[", postfix = "]") {
                "\"" + escapeJson(it.absolutePath.removePrefix("${File(cannoliRoot, "Roms").absolutePath}${File.separator}").replace(File.separatorChar, '/')) + "\""
            }
        )
        sb.append('}')
        return sb.toString()
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

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
