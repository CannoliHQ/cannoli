package dev.cannoli.scorza.ra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RaOfflinePreloader(
    private val client: RaConnectClient,
    private val store: RaOfflineStore,
) {
    sealed interface Result {
        data class Success(val gameName: String, val achievementCount: Int, val totalPoints: Int) : Result
        data object NoAchievements : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun preload(
        romPath: String,
        platformTag: String,
        gameId: Int,
        username: String,
        token: String,
        hash: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        val login = client.login2(username, token)
        if (!login.isOk() || !login.body.looksSuccessful()) {
            return@withContext Result.Failure("login")
        }
        if (!store.writeLogin2(login.body)) return@withContext Result.Failure("write")

        val sets = client.achievementSets(username, token, gameId)
        if (!sets.isOk()) return@withContext Result.Failure("achievementsets")
        val meta = RaSetMetadata.parse(sets.body) ?: return@withContext Result.Failure("parse")
        if (meta.count == 0) return@withContext Result.NoAchievements

        val session = client.startSession(username, token, gameId)
        if (!session.isOk()) return@withContext Result.Failure("startsession")

        if (!store.writeGame(gameId, sets.body, session.body, platformTag, romPath, hash)) {
            return@withContext Result.Failure("write")
        }
        Result.Success(meta.title, meta.count, meta.points)
    }

    private fun RaConnectClient.RawResponse.isOk() = code in 200..299
    private fun String.looksSuccessful(): Boolean =
        try { JSONObject(this).optBoolean("Success", false) } catch (_: Exception) { false }
}
