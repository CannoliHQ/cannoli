package dev.cannoli.core.achievements

import java.io.File

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

    private fun addHash(g: File, hash: String): Boolean {
        val normalized = hash.trim().lowercase()
        if (normalized.isEmpty()) return true
        val known = hashesIn(g)
        if (normalized in known) return true
        return writeAtomic(File(g, HASHES), (known + normalized).joinToString("\n"))
    }

    fun isCached(gameId: Int): Boolean = File(gameDir(gameId), "achievementsets.json").exists()

    fun cachedAtMs(gameId: Int): Long? =
        File(gameDir(gameId), "achievementsets.json").takeIf { it.isFile }?.lastModified()

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
