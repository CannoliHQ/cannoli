package dev.cannoli.scorza.romm

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class RommException(val statusCode: Int?, message: String, cause: Throwable? = null) :
    Exception(message, cause)

class RommClient(
    private val baseUrlProvider: () -> String,
    private val clientProvider: () -> OkHttpClient,
) {
    private val jsonMedia = "application/json".toMediaType()

    fun resolveBaseUrl(typedHost: String): String? {
        for (candidate in RommUrlCandidates.build(typedHost)) {
            val ok = runCatching {
                val request = Request.Builder().url("$candidate/api/heartbeat".toHttpUrl()).get().build()
                clientProvider().newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    fun exchangeCode(code: String): String {
        val payload = ClientTokenExchangePayload(code.trim().replace("-", ""))
        val body = rommJson
            .encodeToString(ClientTokenExchangePayload.serializer(), payload)
            .toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(endpoint("/api/client-tokens/exchange"))
            .post(body)
            .build()
        val dto = execute(request, ClientTokenDto.serializer())
        return dto.rawToken
    }

    fun getPlatforms(): List<PlatformDto> {
        val request = Request.Builder().url(endpoint("/api/platforms")).get().build()
        return execute(request, ListSerializer(PlatformDto.serializer()))
    }

    fun currentUser(): String? = runCatching {
        val request = Request.Builder().url(endpoint("/api/users/me")).get().build()
        execute(request, UserMeDto.serializer()).username.ifEmpty { null }
    }.getOrNull()

    fun serverVersion(): String? = runCatching {
        val request = Request.Builder().url(endpoint("/api/heartbeat")).get().build()
        execute(request, HeartbeatResponse.serializer()).system.version.ifEmpty { null }
    }.getOrNull()

    fun getRoms(platformId: Int, limit: Int, offset: Int, search: String?): RomsPageDto {
        val url = endpoint("/api/roms").newBuilder()
            .addQueryParameter("platform_ids", platformId.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order_by", "name")
            .addQueryParameter("order_dir", "asc")
            .addQueryParameter("with_files", "true")
            .addQueryParameter("with_char_index", "false")
            .addQueryParameter("with_filter_values", "false")
            .apply { if (!search.isNullOrBlank()) addQueryParameter("search_term", search) }
            .build()
        val request = Request.Builder().url(url).get().build()
        return execute(request, RomsPageDto.serializer())
    }

    private fun endpoint(path: String): HttpUrl {
        val base = baseUrlProvider().trimEnd('/')
        return try {
            "$base$path".toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw RommException(null, "Invalid server URL: $base", e)
        }
    }

    private fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T {
        val response: Response = try {
            clientProvider().newCall(request).execute()
        } catch (e: IOException) {
            throw RommException(null, "Network error: ${e.message}", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw RommException(it.code, "HTTP ${it.code}: ${text.take(200)}")
            }
            return try {
                rommJson.decodeFromString(deserializer, text)
            } catch (e: SerializationException) {
                throw RommException(it.code, "Parse error: ${e.message}", e)
            }
        }
    }
}
