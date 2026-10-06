package dev.cannoli.scorza.util

import dev.cannoli.core.RomKey
import dev.cannoli.scorza.config.CannoliPaths
import java.io.File

object SaveStateCoreMigration {

    private const val MARKER = ".states_keyed_by_core"

    data class Result(val games: Int, val files: Int)

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
            if (target.exists()) continue
            if (state.renameTo(target)) moved++
        }
        return moved
    }
}
