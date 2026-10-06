package dev.cannoli.scorza.achievements

import dev.cannoli.core.achievements.RaPendingUnlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RaPendingDrainer(
    private val queue: () -> RaPendingUnlocks,
    private val client: RaConnectClient,
    private val refreshGame: suspend (Int) -> Unit = {},
) {
    data class Result(
        val submitted: Int,
        val left: Int,
        val reached: Boolean = true,
        val refreshed: Set<Int> = emptySet(),
    )

    suspend fun drain(): Result = withContext(Dispatchers.IO) {
        val pending = queue()
        val queued = pending.list()
        if (queued.isEmpty()) return@withContext Result(0, 0)
        var submitted = 0
        var reached = false
        var stopped = false
        for (p in queued) {
            if (stopped) break
            val res = client.replay(p.body)
            if (res.code < 0) {
                stopped = true
                continue
            }
            reached = true
            if (accepted(res.body)) {
                pending.delete(p)
                submitted++
            }
        }
        val left = pending.list()
        val emptied = queued.map { it.gameId }.toSet() - left.map { it.gameId }.toSet()
        for (gameId in emptied) runCatching { refreshGame(gameId) }
        Result(submitted, left.size, reached, emptied)
    }

    private fun accepted(body: String): Boolean =
        RaConnectClient.successFlag(body) == true || body.contains("User already has")
}
