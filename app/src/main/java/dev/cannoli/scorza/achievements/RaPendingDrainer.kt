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
    // A supplier rather than the queue itself: this is built during Hilt's field injection in
    // MainActivity.onCreate, which on a clean install runs before first run has chosen a Cannoli
    // root. Resolving the directory there took the launcher down before it could ask for one.
    private val queue: () -> RaPendingUnlocks,
    private val client: RaConnectClient,
    // What to do about a game whose queue is now empty. Defaulted so a test can watch the drain
    // alone, and wired at the call site to re-preload the set so the cache converges on the unlock
    // state the server now holds.
    private val refreshGame: suspend (Int) -> Unit = {},
) {
    /**
     * [reached] is whether the server answered at all, which a count cannot say: nothing submitted
     * means the network was gone or the server refused every one of them, and only the first is
     * worth telling the player to fix. A queue with nothing in it reached nobody and failed nobody,
     * so it reports true rather than inventing a network problem.
     */
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

    // "User already has" is RetroAchievements saying the unlock is recorded, just not by this call.
    // rc_client treats it as success for the same reason.
    private fun accepted(body: String): Boolean =
        RaConnectClient.successFlag(body) == true || body.contains("User already has")
}
