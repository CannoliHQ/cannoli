package dev.cannoli.igm

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import java.io.File

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
}
