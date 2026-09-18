package dev.cannoli.scorza.achievements

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

open class RaConnectClient(
    private val baseUrlProvider: () -> String = { "https://retroachievements.org" },
    private val clientProvider: () -> OkHttpClient = { sharedClient },
    private val userAgent: String = defaultUserAgent(),
    private val log: (String) -> Unit = {},
) {
    data class RawResponse(val code: Int, val body: String)

    sealed interface LoginResult {
        data class Success(val token: String, val username: String, val score: Int) : LoginResult
        data class InvalidCredentials(val message: String) : LoginResult
        data object NetworkError : LoginResult
    }

    fun validateToken(username: String, token: String): RawResponse =
        post(FormBody.Builder().add("r", "login2").add("u", username).add("t", token).build())

    /** Exchanges a password for an account token. The password never leaves this call. */
    fun loginWithPassword(username: String, password: String): LoginResult {
        val res = post(FormBody.Builder().add("r", "login").add("u", username).add("p", password).build())
        if (res.code !in 200..299) {
            log("ra login failed: code=${res.code} body=${res.body}")
            return LoginResult.NetworkError
        }
        val json = try { JSONObject(res.body) } catch (_: Exception) {
            log("ra login failed: unparseable response, code=${res.code} body=${res.body}")
            return LoginResult.NetworkError
        }
        val token = json.optString("Token", "")
        if (!json.optBoolean("Success", false) || token.isEmpty()) {
            return LoginResult.InvalidCredentials(json.optString("Error", ""))
        }
        return LoginResult.Success(
            token = token,
            username = json.optString("User", username).ifEmpty { username },
            score = json.optInt("Score", 0),
        )
    }

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

    /**
     * Sends a request body the achievement client built, exactly as it built it.
     *
     * Replayed rather than rebuilt: an unlock carries fields only that client can produce, and asking
     * the server the same question it was asked keeps this a courier rather than a second client.
     */
    open fun replay(postData: String): RawResponse = postRaw(postData)

    private fun post(form: FormBody): RawResponse {
        val url = baseUrlProvider().trimEnd('/') + "/dorequest.php"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).post(form).build()
        return try {
            clientProvider().newCall(request).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string() ?: "")
            }
        } catch (e: IOException) {
            log("ra request failed: ${e.javaClass.simpleName}: ${e.message}")
            RawResponse(-1, "")
        }
    }

    private fun postRaw(postData: String): RawResponse {
        val url = baseUrlProvider().trimEnd('/') + "/dorequest.php"
        val body = postData.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url(url).header("User-Agent", userAgent).post(body).build()
        return try {
            clientProvider().newCall(request).execute().use { resp ->
                RawResponse(resp.code, resp.body?.string() ?: "")
            }
        } catch (e: IOException) {
            log("ra replay failed: ${e.javaClass.simpleName}: ${e.message}")
            RawResponse(-1, "")
        }
    }

    companion object {
        // Must track RCHEEVOS_VERSION_MAJOR/MINOR/PATCH in
        // retroarch/deps/rcheevos/src/rc_version.h, the vendored copy RetroArch itself hashes and
        // caches with, since a launch and its cached session are served by the same rcheevos.
        private const val RA_CLIENT_VERSION = "12.4.0"

        /**
         * The user agent the embedded RetroArch sends, because the server identifies the client from
         * it and answers an unrecognised one with a set carrying a "Warning: Unknown Emulator"
         * achievement that always fires. What this fetches is played by that RetroArch.
         *
         * Same shape as `rcheevos_get_user_agent`, with the release parsed the way
         * `frontend_android_get_version` parses `ro.build.version.release`. The core clause RetroArch
         * appends once a core is loaded has no counterpart here, which is the shape it sends itself
         * before one is.
         */
        fun retroArchUserAgent(retroArchVersion: String, androidRelease: String): String {
            val parts = Regex("\\d+").findAll(androidRelease).map { it.value.toInt() }.toList()
            return "RetroArch/$retroArchVersion (Android ${parts.getOrElse(0) { 0 }}.${parts.getOrElse(1) { 0 }})"
        }

        // The default rather than a value each construction site passes: the site that fetches a
        // preload is not the site the achievements module builds, and only one of them carried the
        // agent the server was reading.
        private fun defaultUserAgent(): String = retroArchUserAgent(
            dev.cannoli.scorza.BuildConfig.RETROARCH_VERSION,
            android.os.Build.VERSION.RELEASE.orEmpty(),
        )

        /**
         * The API's Success flag, or null when the body is not the JSON it promises.
         *
         * Null is not false. A 200 carrying a captive portal's HTML says nothing about the
         * credentials, so the callers that can tell the difference report it as unreachable
         * rather than accusing the user of a bad token.
         */
        fun successFlag(body: String): Boolean? = try {
            JSONObject(body).takeIf { it.has("Success") }?.optBoolean("Success", false)
        } catch (_: Exception) {
            null
        }

        /** OkHttp is designed to be shared; one instance reuses the connection pool and dispatcher
         *  across every request instead of spinning up a new pool per call during bulk preload. */
        private val sharedClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
