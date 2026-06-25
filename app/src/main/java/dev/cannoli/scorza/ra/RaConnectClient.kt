package dev.cannoli.scorza.ra

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class RaConnectClient(
    private val baseUrlProvider: () -> String = { "https://retroachievements.org" },
    private val clientProvider: () -> OkHttpClient = { OkHttpClient() },
    private val userAgent: String = "Cannoli",
) {
    data class RawResponse(val code: Int, val body: String)

    fun login2(username: String, token: String): RawResponse =
        post(FormBody.Builder().add("r", "login2").add("u", username).add("t", token).build())

    fun achievementSets(username: String, token: String, gameId: Int): RawResponse =
        post(FormBody.Builder().add("r", "achievementsets").add("u", username).add("t", token)
            .add("g", gameId.toString()).build())

    fun startSession(username: String, token: String, gameId: Int): RawResponse =
        post(FormBody.Builder().add("r", "startsession").add("u", username).add("t", token)
            .add("g", gameId.toString()).add("l", RA_CLIENT_VERSION).build())

    /** Returns the RA game id (0 if the hash is unrecognized), or -1 if the server could not be reached. */
    fun resolveGameId(username: String, token: String, hash: String): Int {
        val res = post(FormBody.Builder().add("r", "gameid").add("u", username).add("t", token)
            .add("m", hash).build())
        if (res.code !in 200..299) return -1
        return try { JSONObject(res.body).optInt("GameID", 0) } catch (_: Exception) { 0 }
    }

    private fun post(form: FormBody): RawResponse {
        val url = baseUrlProvider().trimEnd('/') + "/dorequest.php"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).post(form).build()
        return try {
            clientProvider().newCall(request).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string() ?: "")
            }
        } catch (_: IOException) {
            RawResponse(-1, "")
        }
    }

    companion object {
        private const val RA_CLIENT_VERSION = "12.3.0"
    }
}
