package dev.cannoli.core.achievements

import java.io.File

/**
 * Persistent offline cache for RetroAchievements sets. The source of truth is the on-disk
 * layout itself, not a separate index: each cached game is a `<gameId>/` directory containing
 * `achievementsets.json`, `startsession.json`, a `source` file (platformTag + romPath), and a
 * `hash` file (the rom hash, for offline hash-based serving). `login2.json` at the root is shared.
 */
class RaOfflineStore(
    private val dir: File,
    private val writerTag: String = java.util.UUID.randomUUID().toString().take(8),
) {

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

    /**
     * Writes through a temporary file and renames it into place.
     *
     * Both processes write this cache now, the launcher when it preloads and the game when it plays
     * online, so a reader must never see half a body. Rename is atomic within a filesystem, and the
     * last writer winning is the intended outcome: both are writing the same server's answer.
     *
     * The temporary name carries a per-writer tag: a shared one lets two writers interleave inside
     * the same scratch file and rename the mixture into place, which is the one way this can still
     * publish a half body.
     */
    private fun writeAtomic(target: File, body: String): Boolean = try {
        val tmp = File(target.parentFile, "${target.name}.$writerTag.tmp")
        tmp.writeText(body)
        tmp.renameTo(target) || tmp.copyTo(target, overwrite = true).let { tmp.delete(); true }
    } catch (_: Exception) {
        false
    }

    fun writeLogin2(body: String): Boolean = try {
        dir.mkdirs()
        writeAtomic(login2File, body)
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
            // A writer with no path does not erase one. The game process is handed content by
            // RetroArch and has no launcher-side path to write, while a preload knows the path the
            // offline browser refreshes from, so a session played online would otherwise cost the
            // entry the only path it had.
            val path = romPath.ifEmpty { readSource(g)?.second ?: "" }
            val ok = writeAtomic(File(g, "achievementsets.json"), achievementSets) &&
                writeAtomic(File(g, "startsession.json"), startSession) &&
                writeAtomic(File(g, "source"), "$platformTag\n$path") &&
                (hash.isNullOrEmpty() || addHash(g, hash))
            check(ok)
            true
        } catch (_: Exception) {
            g.deleteRecursively()
            false
        }
    }

    /**
     * Adds this ROM's hash to the ones this game answers for, keeping the ones already there.
     *
     * A manual Game ID deliberately points several ROMs at one game, and each of them asks by its
     * own hash, so a single-hash file meant the newest preload evicted the previous ROM's claim and
     * that game silently stopped being recognised.
     */
    private fun addHash(g: File, hash: String): Boolean {
        val normalized = hash.trim().lowercase()
        if (normalized.isEmpty()) return true
        val known = hashesIn(g)
        if (normalized in known) return true
        return writeAtomic(File(g, HASHES), (known + normalized).joinToString("\n"))
    }

    fun isCached(gameId: Int): Boolean = File(gameDir(gameId), "achievementsets.json").exists()

    /** When this game's set was last written, without parsing it. */
    fun cachedAtMs(gameId: Int): Long? =
        File(gameDir(gameId), "achievementsets.json").takeIf { it.isFile }?.lastModified()

    /** One game's entry, for a caller that knows which game it wants and must not scan the card. */
    fun entry(gameId: Int): Entry? = entryOf(gameDir(gameId))

    fun entries(): List<Entry> = gameDirs().mapNotNull { entryOf(it) }
        .sortedWith(compareBy({ it.platformTag.lowercase() }, { it.gameName.lowercase() }))

    private fun entryOf(g: File): Entry? {
        val gameId = g.name.toIntOrNull() ?: return null
        val setsFile = File(g, "achievementsets.json")
        if (!setsFile.exists()) return null
        val meta = RaSetMetadata.parse(setsFile.readText()) ?: return null
        val (tag, romPath) = readSource(g) ?: return null
        return Entry(
            gameId = gameId,
            romPath = romPath,
            gameName = meta.title,
            platformTag = tag,
            achievementCount = meta.count,
            totalPoints = meta.points,
            cachedAtMs = setsFile.lastModified(),
        )
    }

    fun deleteGame(gameId: Int) {
        gameDir(gameId).deleteRecursively()
        if (gameDirs().isEmpty()) login2File.delete()
    }

    private fun gameDirs(): List<File> =
        dir.listFiles { f -> f.isDirectory && (f.name.toIntOrNull() ?: 0) > 0 }?.toList() ?: emptyList()

    companion object {
        private const val HASHES = "hashes"

        /**
         * The ROM hashes this game answers for.
         *
         * `hash`, holding exactly one, is what a cache written before a game could answer for
         * several ROMs carries, and it is still read so an install keeps serving until its next
         * preload rewrites it.
         */
        internal fun hashesIn(gameDir: File): List<String> = try {
            val plural = File(gameDir, HASHES)
            val text = when {
                plural.isFile -> plural.readText()
                else -> File(gameDir, "hash").takeIf { it.isFile }?.readText() ?: ""
            }
            text.split('\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

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
