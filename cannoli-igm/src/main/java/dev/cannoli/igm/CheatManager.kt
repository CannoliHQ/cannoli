package dev.cannoli.igm

import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    private val log: (String) -> Unit = {},
    private val writer: Executor = sharedWriter,
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

    private val gameKey = "$platformTag/$gameBaseName"

    // The store is read once and then owned in memory. Every toggle persists, and re-reading the
    // file it just wrote costs a FUSE read on the thread the game is drawing from.
    private var cache: MutableMap<String, MutableMap<String, String>>? = null

    private fun sections(): MutableMap<String, MutableMap<String, String>> =
        cache ?: IniParser.parse(stateFile).sections
            .mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }
            .also { cache = it }

    fun loadLastUsed(): LastUsedCheats? {
        val raw = sections()[SECTION]?.get(gameKey) ?: return null
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
        val sections = sections()
        sections.getOrPut(SECTION) { mutableMapOf() }[gameKey] =
            fileName + ":" + hashes.sorted().joinToString(",")
        // v1 wrote one key per cheat file under [enabled], holding positional indexes. Nothing
        // reads it any more, so this game's rows go on the next write and other games' rows stay.
        sections[LEGACY_SECTION]?.keys?.removeAll { it.startsWith("$gameKey/") }
        // The writer thread only ever sees this copy, so it cannot read a map the next toggle is
        // editing, and the queue's order is what makes the last save the one that lands.
        val snapshot = sections.mapValues { it.value.toMap() }
        writer.execute { IniWriter.write(stateFile, snapshot) }
    }

    companion object {
        private const val SECTION = "last_used"
        private const val LEGACY_SECTION = "enabled"

        private val sharedWriter: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cheat-state-writer").apply { isDaemon = true }
        }
    }
}
