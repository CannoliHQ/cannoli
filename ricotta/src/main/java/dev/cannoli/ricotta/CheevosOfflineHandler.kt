package dev.cannoli.ricotta

import dev.cannoli.core.achievements.RaOfflineLookup
import dev.cannoli.core.achievements.RaOfflineStore
import dev.cannoli.core.achievements.RaPendingUnlocks
import dev.cannoli.core.achievements.RaSetMetadata
import org.json.JSONObject

class CheevosOfflineHandler(
    private val store: RaOfflineStore,
    private val lookup: RaOfflineLookup,
    private val pending: RaPendingUnlocks,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile var gameId: Int = 0

    @Volatile private var served = false

    private val failedOnce = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun failed(postData: String) {
        learnGame(postData)
        failedOnce.add(key(postData))
    }

    fun servedFromCache(): Boolean = served

    fun pendingAchievementIds(): Set<Int> = if (gameId <= 0) emptySet() else pending.achievementIdsFor(gameId)

    fun cachedAtMs(): Long? = if (gameId <= 0) null else store.cachedAtMs(gameId)

    fun request(postData: String): String? {
        learnGame(postData)
        if (!failedOnce.contains(key(postData))) return null
        if (lookup.requestType(postData) == RaPendingUnlocks.AWARD) return takeOverUnlock(postData)
        val body = lookup.bodyFor(postData) ?: return null
        served = true
        return body
    }

    fun response(postData: String, body: String, httpStatus: Int): String? {
        learnGame(postData)
        record(postData, body, httpStatus)
        return cachedSetForUnknownGame(postData, body)
    }

    private fun cachedSetForUnknownGame(postData: String, body: String): String? {
        if (lookup.requestType(postData) != "achievementsets") return null
        if (!saysNoSuchGame(body)) return null
        val cached = lookup.bodyFor(postData) ?: return null
        lookup.gameIdFor(postData)?.takeIf { it > 0 }?.let { id ->
            gameId = id
            sets[id] = cached
            RaOfflineLookup.field(postData, "m")?.let { romHash = it }
        }
        return cached
    }

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

    @Volatile var platformTag: String = ""
    @Volatile var romPath: String = ""
    @Volatile var romHash: String? = null

    private val sets = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun takeOverUnlock(postData: String): String? {
        val id = gameId
        if (id <= 0) return null
        if (!pending.write(postData, id, now())) return null
        val achievementId = RaOfflineLookup.field(postData, "a")?.toIntOrNull() ?: return null
        served = true
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

    private fun looksSuccessful(body: String): Boolean = SUCCESS_TRUE.containsMatchIn(body)

    private fun key(postData: String): String {
        val type = lookup.requestType(postData)
        val achievement = if (type == RaPendingUnlocks.AWARD) ":${RaOfflineLookup.field(postData, "a")}" else ""
        return "$type:${gameIdOf(postData)}$achievement"
    }

    companion object {
        private val SUCCESS_TRUE = Regex("\"Success\"\\s*:\\s*true")

        private const val NOT_FOUND = "not_found"
    }
}
