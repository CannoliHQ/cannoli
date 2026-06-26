package dev.cannoli.scorza.ra

import java.io.File

/**
 * Persistent offline cache for RetroAchievements sets. The source of truth is the on-disk
 * layout itself, not a separate index: each cached game is a `<gameId>/` directory containing
 * `achievementsets.json`, `startsession.json`, a `source` file (platformTag + romPath), and a
 * `hash` file (the rom hash, for offline hash-based serving). `login2.json` at the root is shared.
 */
class RaOfflineStore(private val dir: File) {

    data class Entry(
        val gameId: Int,
        val romPath: String,
        val gameName: String,
        val platformTag: String,
        val achievementCount: Int,
        val totalPoints: Int,
        val cachedAtMs: Long,
    )

    private val login2File get() = File(dir, "login2.json")
    private fun gameDir(gameId: Int) = File(dir, gameId.toString())

    fun writeLogin2(body: String): Boolean = try {
        dir.mkdirs()
        login2File.writeText(body)
        true
    } catch (_: Exception) {
        false
    }

    /** Writes a game's cached bodies atomically: a partial-write failure deletes the dir and
     *  returns false so [entries] never surfaces a corrupt entry. */
    fun writeGame(
        gameId: Int,
        achievementSets: String,
        startSession: String,
        platformTag: String,
        romPath: String,
        hash: String?,
    ): Boolean {
        val g = gameDir(gameId)
        return try {
            g.mkdirs()
            File(g, "achievementsets.json").writeText(achievementSets)
            File(g, "startsession.json").writeText(startSession)
            File(g, "source").writeText("$platformTag\n$romPath")
            if (!hash.isNullOrEmpty()) File(g, "hash").writeText(hash)
            true
        } catch (_: Exception) {
            g.deleteRecursively()
            false
        }
    }

    fun isCached(gameId: Int): Boolean = File(gameDir(gameId), "achievementsets.json").exists()

    fun entries(): List<Entry> = gameDirs().mapNotNull { g ->
        val gameId = g.name.toIntOrNull() ?: return@mapNotNull null
        val setsFile = File(g, "achievementsets.json")
        if (!setsFile.exists()) return@mapNotNull null
        val meta = RaSetMetadata.parse(setsFile.readText()) ?: return@mapNotNull null
        val (tag, romPath) = readSource(g) ?: return@mapNotNull null
        Entry(
            gameId = gameId,
            romPath = romPath,
            gameName = meta.title,
            platformTag = tag,
            achievementCount = meta.count,
            totalPoints = meta.points,
            cachedAtMs = setsFile.lastModified(),
        )
    }.sortedWith(compareBy({ it.platformTag.lowercase() }, { it.gameName.lowercase() }))

    fun deleteGame(gameId: Int) {
        gameDir(gameId).deleteRecursively()
        if (gameDirs().isEmpty()) login2File.delete()
    }

    private fun gameDirs(): List<File> =
        dir.listFiles { f -> f.isDirectory && (f.name.toIntOrNull() ?: 0) > 0 }?.toList() ?: emptyList()

    /** Returns (platformTag, romPath), or null when the source file is missing, unreadable, or has
     *  a blank platform tag, so corrupt entries are dropped instead of surfacing as empty groups. */
    private fun readSource(g: File): Pair<String, String>? {
        val f = File(g, "source")
        if (!f.exists()) return null
        return try {
            val lines = f.readText().split('\n')
            val tag = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            tag to (lines.getOrNull(1) ?: "")
        } catch (_: Exception) {
            null
        }
    }
}
