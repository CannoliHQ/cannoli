package dev.cannoli.scorza.scanner

import android.content.res.AssetManager

data class CoreInfo(
    val id: String,
    val displayName: String,
    val databases: List<String>
)

class CoreInfoRepository(private val assets: AssetManager) {

    private val cores = mutableListOf<CoreInfo>()

    private val tagToDatabases = mapOf(
        "GB" to listOf("Nintendo - Game Boy"),
        "GBC" to listOf("Nintendo - Game Boy Color"),
        "GBA" to listOf("Nintendo - Game Boy Advance"),
        "NES" to listOf("Nintendo - Nintendo Entertainment System", "Nintendo - Family Computer Disk System"),
        "SNES" to listOf("Nintendo - Super Nintendo Entertainment System", "Nintendo - Sufami Turbo", "Nintendo - Satellaview"),
        "N64" to listOf("Nintendo - Nintendo 64"),
        "NDS" to listOf("Nintendo - Nintendo DS"),
        "GG" to listOf("Sega - Game Gear"),
        "SMS" to listOf("Sega - Master System - Mark III"),
        "MD" to listOf("Sega - Mega Drive - Genesis"),
        "32X" to listOf("Sega - 32X"),
        "SCD" to listOf("Sega - Mega-CD - Sega CD"),
        "SAT" to listOf("Sega - Saturn"),
        "PS" to listOf("Sony - PlayStation"),
        "PSP" to listOf("Sony - PlayStation Portable"),
        "LYNX" to listOf("Atari - Lynx"),
        "JAGU" to listOf("Atari - Jaguar"),
        "PCE" to listOf("NEC - PC Engine - TurboGrafx 16", "NEC - PC Engine CD - TurboGrafx-CD"),
        "PCFX" to listOf("NEC - PC-FX"),
        "NGP" to listOf("SNK - Neo Geo Pocket", "SNK - Neo Geo Pocket Color"),
        "WSC" to listOf("Bandai - WonderSwan", "Bandai - WonderSwan Color"),
        "MAME" to listOf("MAME", "MAME 2003-Plus"),
        "FBN" to listOf("FBNeo - Arcade Games"),
        "VBOY" to listOf("Nintendo - Virtual Boy"),
        "POKE" to listOf("Nintendo - Pokemon Mini"),
        "AMOR" to listOf("Commodore - Amiga"),
        "DOS" to listOf("DOS"),
        "SCUM" to listOf("ScummVM")
    )

    fun load() {
        cores.clear()
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
                cores.add(CoreInfo(id, displayName!!, databases))
            }
        }
    }

    fun getDisplayName(coreId: String): String {
        return cores.firstOrNull { it.id == coreId }?.displayName ?: coreId
    }

    fun getCoresForTag(tag: String): List<CoreInfo> {
        val dbs = tagToDatabases[tag] ?: return emptyList()
        return cores.filter { core -> core.databases.any { it in dbs } }
            .sortedBy { it.displayName }
    }
}
