package dev.cannoli.ricotta

import dev.cannoli.core.achievements.RaOfflineLookup
import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.core.achievements.RaPendingUnlocks
import dev.cannoli.core.achievements.RaSetMetadata
import org.json.JSONObject

/**
 * Answers the achievement client from Cannoli's cache when the server cannot be reached or does not
 * recognise the ROM, and records what the server says the rest of the time.
 *
 * Only ever built for a softcore session. The bridge withholds it in hardcore, because everything
 * here can put words in the server's mouth and a hardcore run has to reach the real server or fail.
 *
 * Every request is answered only after its own attempt has failed, an unlock included: a cached set
 * served while the network works would freeze a game's achievements at whatever was cached, and a
 * spoofed unlock would keep the score the server sent out of the body the client reads. An unlock is
 * still never lost, because the failure path records the failure before it asks the cache, so the
 * one request that has failed is taken over on the spot and queued for the launcher to replay.
 */
class CheevosOfflineHandler(
    private val store: RaOfflineStore,
    private val lookup: RaOfflineLookup,
    private val pending: RaPendingUnlocks,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * The game this session is for, learned from the requests themselves.
     *
     * An unlock request carries an achievement id and no game id, so the game has to be remembered
     * from the set or session request that preceded it. Offline and with no id to send, the client
     * asks by ROM hash, and the cached directory that answers is what names the game.
     */
    @Volatile var gameId: Int = 0

    @Volatile private var served = false

    private val failedOnce = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Records that the network attempt for this request failed, so the next ask may use the cache. */
    fun failed(postData: String) {
        learnGame(postData)
        failedOnce.add(key(postData))
    }

    fun servedFromCache(): Boolean = served

    /** What the queue is holding for this game, so the in-game list can mark those rows unsynced. */
    fun pendingAchievementIds(): Set<Int> = if (gameId <= 0) emptySet() else pending.achievementIdsFor(gameId)

    /** When this game's cache was written, for a status line the menu can show relative to now. */
    fun cachedAtMs(): Long? = if (gameId <= 0) null else store.cachedAtMs(gameId)

    fun request(postData: String): String? {
        learnGame(postData)
        if (!failedOnce.contains(key(postData))) return null
        if (lookup.requestType(postData) == RaPendingUnlocks.AWARD) return takeOverUnlock(postData)
        val body = lookup.bodyFor(postData) ?: return null
        served = true
        return body
    }

    /**
     * Sees what the server said, and may answer in its place.
     *
     * The record is unconditional and unchanged; the return is the substitution, and today only one
     * response earns one. Null means the client uses what the server sent.
     */
    fun response(postData: String, body: String, httpStatus: Int): String? {
        learnGame(postData)
        record(postData, body, httpStatus)
        return cachedSetForUnknownGame(postData, body)
    }

    /**
     * The cached set for a ROM the server does not recognise.
     *
     * A manual RA game id override is the case this exists for. The launcher preloads the overridden
     * game's set and files it under this ROM's hash, but the game process only ever sends the hash,
     * and the server has never seen it, so the load is abandoned before a session is ever asked for.
     * The set response is therefore the only place the cache can step in, and once it does the
     * session that follows carries the overridden id and the server takes it.
     */
    private fun cachedSetForUnknownGame(postData: String, body: String): String? {
        if (lookup.requestType(postData) != "achievementsets") return null
        if (!saysNoSuchGame(body)) return null
        // Deliberately not flagged as served from cache: the network is up, the session, the pings
        // and every unlock are the server's, and only the set definitions came from here.
        return lookup.bodyFor(postData)
    }

    /**
     * The server saying it has no such game, as opposed to failing to answer the question.
     *
     * Only two bodies mean it, and the difference matters: a 5xx, an unparseable body and an expired
     * token all leave the game equally unidentified, and substituting for those would hide a real
     * error behind stale definitions the player cannot tell apart from live ones.
     *
     * rc_client draws the same line in `rc_client_fetch_game_sets_callback`. It treats any error
     * message as a load failure except `RC_NOT_FOUND`, and only then falls through to its
     * `fetch_game_sets_response.id == 0` branch. `RC_NOT_FOUND` comes from exactly one place,
     * `rc_json_convert_error_code` mapping the `not_found` error code; every other code maps to a
     * result that fails the load. The other way into that branch is a response that parsed and
     * succeeded and still carries a `GameId` of zero. Those two are what this accepts.
     */
    private fun saysNoSuchGame(body: String): Boolean {
        val obj = try { JSONObject(body) } catch (_: Exception) { return false }
        if (obj.optBoolean("Success", false)) return obj.optInt("GameId", -1) == 0
        return obj.optString("Code", "") == NOT_FOUND
    }

    private fun record(postData: String, body: String, httpStatus: Int) {
        if (httpStatus !in 200..299 || body.isEmpty()) return
        if (!looksSuccessful(body)) return
        when (lookup.requestType(postData)) {
            "login2" -> store.writeLogin2(body)
            "achievementsets" -> {
                // The id comes from the answer, not the ask: rc_client's first sets request carries
                // the ROM hash and no game id at all, so keying on the request would file every
                // never-preloaded game under 0 and the session would never find it again.
                val id = RaSetMetadata.parse(body)?.gameId?.takeIf { it > 0 } ?: return
                RaOfflineLookup.field(postData, "m")?.let { romHash = it }
                gameId = id
                sets[id] = body
            }
            "startsession" -> {
                val id = gameIdOf(postData)
                val cachedSets = sets.remove(id) ?: return
                if (id > 0) store.writeGame(id, cachedSets, body, platformTag, romPath, romHash)
            }
        }
    }

    /**
     * What a write-through entry records beyond the bodies themselves.
     *
     * The platform comes from the launch parcel. The ROM path does not: the game process was handed
     * content by RetroArch and has no launcher-side path to write, so it stays empty and the offline
     * browser's refresh works from the game id it already passes. A launcher preload of the same game
     * fills the path in.
     */
    @Volatile var platformTag: String = ""
    @Volatile var romPath: String = ""
    @Volatile var romHash: String? = null

    // A set and its session arrive as two responses and the store writes them together, so the first
    // waits here for the second rather than being written half complete.
    private val sets = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun takeOverUnlock(postData: String): String? {
        val id = gameId
        if (id <= 0) return null
        if (!pending.write(postData, id, now())) return null
        val achievementId = RaOfflineLookup.field(postData, "a")?.toIntOrNull() ?: return null
        served = true
        // The fields rc_client actually reads back: anything absent defaults, and a body it cannot
        // parse would be treated as a failure and retried, which is the outcome this exists to avoid.
        return """{"Success":true,"AchievementID":$achievementId}"""
    }

    private fun learnGame(postData: String) {
        val id = RaOfflineLookup.field(postData, "g")?.toIntOrNull()?.takeIf { it > 0 }
            ?: lookup.gameIdFor(postData)
            ?: return
        gameId = id
    }

    private fun gameIdOf(postData: String): Int =
        RaOfflineLookup.field(postData, "g")?.toIntOrNull() ?: gameId

    // The server saying yes, rather than merely not saying no: an error page or a truncated body
    // contains neither field, and caching one would serve it back as though it were a real answer.
    private fun looksSuccessful(body: String): Boolean = SUCCESS_TRUE.containsMatchIn(body)

    // Two requests differ only by their fields, so the type plus the game is what identifies an
    // attempt. The token is deliberately not part of it: it rotates, and a rotation must not look
    // like a different request that has never failed. An unlock adds its achievement id, because a
    // game sends many and each deserves its own try at the network rather than inheriting the first
    // one's failure.
    private fun key(postData: String): String {
        val type = lookup.requestType(postData)
        val achievement = if (type == RaPendingUnlocks.AWARD) ":${RaOfflineLookup.field(postData, "a")}" else ""
        return "$type:${gameIdOf(postData)}$achievement"
    }

    companion object {
        private val SUCCESS_TRUE = Regex("\"Success\"\\s*:\\s*true")

        // rc_json_convert_error_code's one mapping onto RC_NOT_FOUND, spelled as the server spells it.
        private const val NOT_FOUND = "not_found"
    }
}
