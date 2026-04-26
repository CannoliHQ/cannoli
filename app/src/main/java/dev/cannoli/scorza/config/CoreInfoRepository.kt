package dev.cannoli.scorza.config

import android.content.res.AssetManager
import java.io.File

data class CoreInfo(
    val id: String,
    val displayName: String,
    val databases: List<String>
)

data class FirmwareEntry(
    val path: String,
    val desc: String,
    val optional: Boolean
)

class CoreInfoRepository(private val assets: AssetManager, private val cacheDir: File? = null, private val apkLastModified: Long = 0L) {

    @Volatile private var cores = listOf<CoreInfo>()
    @Volatile private var coreById = mapOf<String, CoreInfo>()

    private val tagToDatabases = mapOf(
        "GB" to listOf("Nintendo - Game Boy"),
        "GBC" to listOf("Nintendo - Game Boy Color"),
        "GBA" to listOf("Nintendo - Game Boy Advance"),
        "NES" to listOf("Nintendo - Nintendo Entertainment System", "Nintendo - Family Computer Disk System"),
        "FDS" to listOf("Nintendo - Family Computer Disk System"),
        "SNES" to listOf("Nintendo - Super Nintendo Entertainment System", "Nintendo - Sufami Turbo", "Nintendo - Satellaview"),
        "N64" to listOf("Nintendo - Nintendo 64"),
        "NDS" to listOf("Nintendo - Nintendo DS"),
        "GG" to listOf("Sega - Game Gear"),
        "SMS" to listOf("Sega - Master System - Mark III"),
        "MD" to listOf("Sega - Mega Drive - Genesis"),
        "SG1000" to listOf("Sega - SG-1000"),
        "32X" to listOf("Sega - 32X"),
        "SEGACD" to listOf("Sega - Mega-CD - Sega CD"),
        "SATURN" to listOf("Sega - Saturn"),
        "PS" to listOf("Sony - PlayStation"),
        "PSP" to listOf("Sony - PlayStation Portable"),
        "DC" to listOf("Sega - Dreamcast"),
        "LYNX" to listOf("Atari - Lynx"),
        "JAGUAR" to listOf("Atari - Jaguar"),
        "ATARI2600" to listOf("Atari - 2600"),
        "ATARI5200" to listOf("Atari - 5200"),
        "ATARI7800" to listOf("Atari - 7800"),
        "PCE" to listOf("NEC - PC Engine - TurboGrafx 16", "NEC - PC Engine CD - TurboGrafx-CD"),
        "SUPERGRAFX" to listOf("NEC - PC Engine SuperGrafx"),
        "PCFX" to listOf("NEC - PC-FX"),
        "NEOGEO" to listOf("SNK - Neo Geo"),
        "NGP" to listOf("SNK - Neo Geo Pocket"),
        "NGPC" to listOf("SNK - Neo Geo Pocket Color"),
        "WS" to listOf("Bandai - WonderSwan"),
        "WSC" to listOf("Bandai - WonderSwan Color"),
        "MAME" to listOf("MAME", "MAME 2003-Plus", "MAME 2000", "MAME 2003", "MAME 2003 (Midway)", "MAME 2010"),
        "FBN" to listOf("FBNeo - Arcade Games"),
        "VIRTUALBOY" to listOf("Nintendo - Virtual Boy"),
        "POKEMINI" to listOf("Nintendo - Pokemon Mini"),
        "COLECOVISION" to listOf("Coleco - ColecoVision"),
        "VECTREX" to listOf("GCE - Vectrex"),
        "INTELLIVISION" to listOf("Mattel - Intellivision"),
        "AMIGA" to listOf("Commodore - Amiga"),
        "AMIGA500" to listOf("Commodore - Amiga"),
        "AMIGA1200" to listOf("Commodore - Amiga"),
        "DOS" to listOf("DOS"),
        "SCUMMVM" to listOf("ScummVM")
    )

    fun load() {
        val cached = loadFromCache()
        if (cached != null) {
            cores = cached
            coreById = cached.associateBy { it.id }
            return
        }

        val result = mutableListOf<CoreInfo>()
        val files = try { assets.list("core_info") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        for (filename in files) {
            if (!filename.endsWith(".info")) continue
            val id = filename.removeSuffix(".info")
            var displayName: String? = null
            val databases = mutableListOf<String>()
            try {
                assets.open("core_info/$filename").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (displayName == null && trimmed.startsWith("corename")) {
                            displayName = trimmed.substringAfter('=').trim().removeSurrounding("\"")
                        } else if (databases.isEmpty() && trimmed.startsWith("database")) {
                            val value = trimmed.substringAfter('=').trim().removeSurrounding("\"")
                            databases.addAll(value.split('|').map { it.trim() })
                        }
                        if (displayName != null && databases.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {}
            if (displayName != null) {
                result.add(CoreInfo(id, displayName, databases))
            }
        }
        cores = result
        coreById = result.associateBy { it.id }
        saveToCache(result)
    }

    private fun loadFromCache(): List<CoreInfo>? {
        val dir = cacheDir ?: return null
        val versionFile = File(dir, ".core_info_version")
        val cacheFile = File(dir, "core_info.cache")
        if (!versionFile.exists() || !cacheFile.exists()) return null
        if (versionFile.readText().trim() != apkLastModified.toString()) return null
        return try {
            cacheFile.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size == 3) CoreInfo(parts[0], parts[1], parts[2].split('|')) else null
            }
        } catch (_: Exception) { null }
    }

    private fun saveToCache(cores: List<CoreInfo>) {
        val dir = cacheDir ?: return
        dir.mkdirs()
        try {
            File(dir, "core_info.cache").writeText(
                cores.joinToString("\n") { "${it.id}\t${it.displayName}\t${it.databases.joinToString("|")}" }
            )
            File(dir, ".core_info_version").writeText(apkLastModified.toString())
        } catch (_: Exception) {}
    }

    fun getDisplayName(coreId: String): String {
        return coreById[coreId]?.displayName ?: coreId
    }

    fun getCoresForTag(tag: String): List<CoreInfo> {
        val dbs = tagToDatabases[tag.uppercase()] ?: return emptyList()
        return cores.filter { core -> core.databases.any { it in dbs } }
            .sortedBy { it.displayName }
    }

    fun getFirmwareFor(coreId: String): List<FirmwareEntry> {
        val filename = "$coreId.info"
        val fields = mutableMapOf<String, String>()
        try {
            assets.open("core_info/$filename").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("firmware")) continue
                    val eq = trimmed.indexOf('=')
                    if (eq < 0) continue
                    val key = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
                    fields[key] = value
                }
            }
        } catch (_: Exception) { return emptyList() }

        val count = fields["firmware_count"]?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val path = fields["firmware${i}_path"] ?: return@mapNotNull null
            val desc = fields["firmware${i}_desc"] ?: path
            val optional = fields["firmware${i}_opt"]?.equals("true", ignoreCase = true) ?: false
            FirmwareEntry(path, desc, optional)
        }
    }

    fun getMissingFirmware(coreId: String, biosDir: File): List<FirmwareEntry> {
        val all = getFirmwareFor(coreId)
        if (all.isEmpty()) return emptyList()
        return all.filter { !it.optional && !File(biosDir, it.path).exists() }
    }
}
