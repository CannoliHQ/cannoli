package dev.cannoli.scorza.achievements

import dev.cannoli.core.achievements.RaPendingUnlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Submits the unlocks the game process took responsibility for.
 *
 * Oldest first, and it stops at the first transport failure rather than working through a queue the
 * network cannot carry. A refusal is different from a failure: the server answered, so the unlock is
 * kept and the next drain tries again, except for the one refusal that means the unlock is already
 * there, which is success arriving by another route.
 */
class RaPendingDrainer(
    private val pending: RaPendingUnlocks,
    private val client: RaConnectClient,
    // What to do about a game whose queue is now empty. Defaulted so a test can watch the drain
    // alone, and wired at the call site to re-preload the set so the cache converges on the unlock
    // state the server now holds.
    private val refreshGame: suspend (Int) -> Unit = {},
) {
    data class Result(val submitted: Int, val left: Int, val refreshed: Set<Int> = emptySet())

    suspend fun drain(): Result = withContext(Dispatchers.IO) {
        val queued = pending.list()
        if (queued.isEmpty()) return@withContext Result(0, 0)
        var submitted = 0
        var stopped = false
        for (p in queued) {
            if (stopped) break
            val res = client.replay(p.body)
            if (res.code < 0) {
                stopped = true
                continue
            }
            if (accepted(res.body)) {
                pending.delete(p)
                submitted++
            }
        }
        val left = pending.list()
        val emptied = queued.map { it.gameId }.toSet() - left.map { it.gameId }.toSet()
        for (gameId in emptied) runCatching { refreshGame(gameId) }
        Result(submitted, left.size, emptied)
    }

    // "User already has" is RetroAchievements saying the unlock is recorded, just not by this call.
    // rc_client treats it as success for the same reason.
    private fun accepted(body: String): Boolean =
        RaConnectClient.successFlag(body) == true || body.contains("User already has")
}
