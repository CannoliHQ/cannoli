package dev.cannoli.scorza.util

import dev.cannoli.core.RomKey
import dev.cannoli.scorza.config.CannoliPaths
import java.io.File

/**
 * Move save states written before they were keyed by core into the folder of the core that will
 * load them.
 *
 * A one-time upgrade concern, so it runs once at boot rather than being checked on every launch
 * forever. It does not need the disk scan: the roms and their overrides are already in the database
 * from the previous run, which is exactly where a v1 install's are.
 *
 * Nothing records which core wrote a state, so adopting it is a guess: the core the game runs on
 * now, its own override first and then the platform's mapping. Right whenever the user has not
 * switched cores, and when wrong the state was written by a core they moved away from, which would
 * have crashed on resume before this change too. Resolving the override matters: sending an
 * overridden game's state to the platform's core would move a working state away from the only core
 * that can load it, which is a regression rather than a missed rescue.
 *
 * Files that carry the ROM's name and `.state` move together, thumbnails included. RetroArch writes
 * a `.png` beside each state, and leaving it behind orphans it and costs the picker its preview.
 */
object SaveStateCoreMigration {

    private const val MARKER = ".states_keyed_by_core"

    data class Result(val games: Int, val files: Int)

    /**
     * [coreFor] answers which core a game folder is mapped to, given its platform tag and folder
     * name. A null answer leaves that game alone: a platform mapped to a standalone app has no core
     * to key by, and its states are not Cannoli's to move.
     */
    fun run(paths: CannoliPaths, coreFor: (tag: String, romBaseName: String) -> String?): Result {
        val root = paths.saveStatesDir
        if (!root.isDirectory) return Result(0, 0)
        val marker = File(root, MARKER)
        if (marker.exists()) return Result(0, 0)

        var games = 0
        var files = 0
        for (tagDir in root.listFiles { f: File -> f.isDirectory }.orEmpty()) {
            for (gameDir in tagDir.listFiles { f: File -> f.isDirectory }.orEmpty()) {
                val loose = looseStates(gameDir)
                if (loose.isEmpty()) continue
                val core = coreFor(tagDir.name, RomKey.normalize(gameDir.name)) ?: continue
                val moved = adopt(loose, File(gameDir, core))
                if (moved > 0) {
                    games++
                    files += moved
                }
            }
        }
        // Written whatever happened, including when nothing moved. The marker records that the
        // upgrade was considered, not that it found work.
        runCatching { marker.writeText("$games games, $files files") }
        return Result(games, files)
    }

    private fun looseStates(gameDir: File): List<File> =
        gameDir.listFiles { f: File -> f.isFile && f.name.contains(".state") }?.toList().orEmpty()

    private fun adopt(loose: List<File>, coreDir: File): Int {
        if (!coreDir.isDirectory && !coreDir.mkdirs()) return 0
        var moved = 0
        for (state in loose) {
            val target = File(coreDir, state.name)
            // Never overwrite. A slot already in the core's folder was written after the change, so
            // it is the newer state and the loose file is what it replaced.
            if (target.exists()) continue
            if (state.renameTo(target)) moved++
        }
        return moved
    }
}
