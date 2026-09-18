package dev.cannoli.core.achievements

import java.io.File

/**
 * Unlocks the achievement client believed it submitted, kept until the launcher really submits them.
 *
 * One file per unlock rather than one file with many lines: the game process writes these and the
 * launcher deletes them, and two processes editing one file is how a queue loses an entry. The name
 * carries the facts a reader needs, so listing the queue costs no reads, and the body is the request
 * the client built, replayed later rather than rebuilt.
 */
class RaPendingUnlocks(
    private val dir: File,
    private val writerTag: String = java.util.UUID.randomUUID().toString().take(8),
) {

    data class Pending(
        val file: File,
        val gameId: Int,
        val achievementId: Int,
        val atMs: Long,
        val body: String,
    )

    fun write(postData: String, gameId: Int, atMs: Long): Boolean {
        if (RaOfflineLookup.field(postData, "r") != AWARD) return false
        val achievementId = RaOfflineLookup.field(postData, "a")?.toIntOrNull() ?: return false
        if (achievementId <= 0 || gameId <= 0) return false
        return try {
            dir.mkdirs()
            // Named by game and achievement so the same unlock arriving twice overwrites rather than
            // queueing twice; the server would dedupe it, but the count the user is shown should not
            // claim two.
            val target = File(dir, "$gameId-$achievementId.req")
            // Written aside and renamed in: a process death partway through a direct write leaves a
            // file that parses as nothing, and an unlock nothing can parse is never submitted and
            // never deleted. The scratch name does not end in .req, so a reader skips it.
            val tmp = File(dir, "$gameId-$achievementId.$writerTag.tmp")
            tmp.writeText("$atMs\n$postData")
            tmp.renameTo(target) || tmp.copyTo(target, overwrite = true).let { tmp.delete(); true }
        } catch (_: Exception) {
            false
        }
    }

    fun list(): List<Pending> = (dir.listFiles() ?: emptyArray())
        .asSequence()
        .filter { it.isFile && it.name.endsWith(".req") }
        .mapNotNull { parse(it) }
        .sortedBy { it.atMs }
        .toList()

    fun delete(p: Pending): Boolean = try {
        p.file.delete()
    } catch (_: Exception) {
        false
    }

    fun countByGame(): Map<Int, Int> = list().groupingBy { it.gameId }.eachCount()

    fun achievementIdsFor(gameId: Int): Set<Int> =
        list().filter { it.gameId == gameId }.mapTo(mutableSetOf()) { it.achievementId }

    private fun parse(f: File): Pending? {
        val name = f.name.removeSuffix(".req").split('-')
        val gameId = name.getOrNull(0)?.toIntOrNull() ?: return null
        val achievementId = name.getOrNull(1)?.toIntOrNull() ?: return null
        val text = try {
            f.readText()
        } catch (_: Exception) {
            return null
        }
        val newline = text.indexOf('\n')
        if (newline <= 0) return null
        val atMs = text.substring(0, newline).trim().toLongOrNull() ?: return null
        return Pending(f, gameId, achievementId, atMs, text.substring(newline + 1))
    }

    companion object {
        const val AWARD = "awardachievement"
    }
}
