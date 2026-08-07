package dev.cannoli.igm

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import java.io.File

object CheatIdentity {
    fun of(desc: String, code: String): String = "$desc|$code"

    // String.hashCode is specified by the JLS, so this value is stable across processes and
    // devices. It only has to survive a .cht file being reordered or re-edited, which the v1
    // index format did not.
    fun hash(desc: String, code: String): String = "%08x".format(of(desc, code).hashCode())
}

data class LastUsedCheats(val fileName: String, val hashes: Set<String>)

class CheatManager(
    cannoliRoot: String,
    private val platformTag: String,
    private val gameBaseName: String,
    private val log: (String) -> Unit = {}
) {
    private val root = File(cannoliRoot)
    private val cheatsDir = File(File(File(root, "Cheats"), platformTag), gameBaseName)
    private val stateFile = File(File(File(root, "Config"), "State"), "cheat_state.ini")

    fun findCheatFiles(): List<CheatFile> {
        if (!cheatsDir.isDirectory) return emptyList()
        val files = cheatsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.extension.equals("cht", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f ->
                val cheats = try {
                    ChtParser.parse(f.readText())
                } catch (e: Exception) {
                    log("cheats: failed to read ${f.name}: ${e.message}")
                    emptyList()
                }
                if (cheats.isEmpty()) {
                    log("cheats: no valid cheats in ${f.name}, ignoring")
                    null
                } else {
                    CheatFile(f, cheats)
                }
            }
    }

    fun rawSnapshot(): List<Pair<String, Long>> {
        if (!cheatsDir.isDirectory) return emptyList()
        val files = cheatsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.extension.equals("cht", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { it.name to it.lastModified() }
    }

    private val keyPrefix = "$platformTag/$gameBaseName/"

    fun loadRemembered(): Map<String, Set<Int>> =
        IniParser.parse(stateFile).getSection("enabled")
            .filterKeys { it.startsWith(keyPrefix) }
            .entries.associate { (k, v) ->
                k.removePrefix(keyPrefix) to
                    v.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            }

    fun saveRemembered(sets: Map<String, Set<Int>>) {
        val sections = IniParser.parse(stateFile).sections.toMutableMap()
        val enabled = (sections["enabled"] ?: emptyMap()).toMutableMap()
        enabled.keys.removeAll { it.startsWith(keyPrefix) }
        for ((fileName, indexes) in sets) {
            if (indexes.isEmpty()) continue
            enabled[keyPrefix + fileName] = indexes.sorted().joinToString(",")
        }
        sections["enabled"] = enabled
        IniWriter.write(stateFile, sections)
    }

    private val gameKey = "$platformTag/$gameBaseName"

    fun loadLastUsed(): LastUsedCheats? {
        val raw = IniParser.parse(stateFile).get(SECTION, gameKey) ?: return null
        val fileName = raw.substringBefore(':', "")
        if (fileName.isEmpty()) return null
        val hashes = raw.substringAfter(':', "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return LastUsedCheats(fileName, hashes)
    }

    fun saveLastUsed(fileName: String, hashes: Set<String>) {
        val sections = IniParser.parse(stateFile).sections.toMutableMap()
        val current = (sections[SECTION] ?: emptyMap()).toMutableMap()
        current[gameKey] = fileName + ":" + hashes.sorted().joinToString(",")
        sections[SECTION] = current
        // v1 wrote one key per cheat file under [enabled], holding positional indexes. Nothing
        // reads it any more, so this game's rows go on the next write and other games' rows stay.
        val legacy = (sections[LEGACY_SECTION] ?: emptyMap()).toMutableMap()
        if (legacy.keys.removeAll { it.startsWith("$gameKey/") }) sections[LEGACY_SECTION] = legacy
        IniWriter.write(stateFile, sections)
    }

    companion object {
        private const val SECTION = "last_used"
        private const val LEGACY_SECTION = "enabled"
    }
}
