package dev.cannoli.scorza.romm

import dev.cannoli.scorza.romm.sync.ConfirmDownloadPayload
import dev.cannoli.scorza.romm.sync.DeleteSavesPayload
import dev.cannoli.scorza.romm.sync.DeviceRegisterPayload
import dev.cannoli.scorza.romm.sync.DeviceRegisterResponse
import dev.cannoli.scorza.romm.sync.RommSaveDto
import dev.cannoli.scorza.romm.sync.SyncCompletePayload
import dev.cannoli.scorza.romm.sync.SyncNegotiatePayload
import dev.cannoli.scorza.romm.sync.SyncNegotiateResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

class RommException(val statusCode: Int?, message: String, cause: Throwable? = null) :
    Exception(message, cause)

class RommDownloadCancelled : Exception("download cancelled")

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

    fun getPlatforms(updatedAfter: String? = null): List<PlatformDto> {
        val url = endpoint("/api/platforms").newBuilder()
            .apply { if (!updatedAfter.isNullOrBlank()) addQueryParameter("updated_after", updatedAfter) }
            .build()
        val request = Request.Builder().url(url).get().build()
        return execute(request, ListSerializer(PlatformDto.serializer()))
    }

    fun getCollections(group: RommCollectionGroup): List<RommNetworkCollection> {
        val request = Request.Builder().url(endpoint(group.apiPath)).get().build()
        return if (group == RommCollectionGroup.VIRTUAL) {
            execute(request, ListSerializer(VirtualCollectionDto.serializer()))
                .map { RommNetworkCollection(it.id, group, it.name, it.romIds, it.romCount) }
        } else {
            execute(request, ListSerializer(CollectionDto.serializer()))
                .map { RommNetworkCollection(it.id.toString(), group, it.name, it.romIds, it.romCount) }
        }
    }

    fun currentUser(): String? = runCatching {
        val request = Request.Builder().url(endpoint("/api/users/me")).get().build()
        execute(request, UserMeDto.serializer()).username.ifEmpty { null }
    }.getOrNull()

    fun serverVersion(): String? = runCatching {
        val request = Request.Builder().url(endpoint("/api/heartbeat")).get().build()
        execute(request, HeartbeatResponse.serializer()).system.version.ifEmpty { null }
    }.getOrNull()

    fun getRoms(
        platformId: Int?,
        limit: Int,
        offset: Int,
        search: String?,
        updatedAfter: String? = null,
    ): RomsPageDto {
        val url = endpoint("/api/roms").newBuilder()
            .apply { if (platformId != null) addQueryParameter("platform_ids", platformId.toString()) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order_by", "name")
            .addQueryParameter("order_dir", "asc")
            .addQueryParameter("with_files", "true")
            .addQueryParameter("with_char_index", "false")
            .addQueryParameter("with_filter_values", "false")
            .apply { if (!search.isNullOrBlank()) addQueryParameter("search_term", search) }
            .apply { if (!updatedAfter.isNullOrBlank()) addQueryParameter("updated_after", updatedAfter) }
            .build()
        val request = Request.Builder().url(url).get().build()
        return execute(request, RomsPageDto.serializer())
    }

    /** All rom IDs currently on the server, used to purge cached games that were deleted server-side. */
    fun getRomIdentifiers(): List<Int> {
        val request = Request.Builder().url(endpoint("/api/roms/identifiers")).get().build()
        return execute(request, ListSerializer(Int.serializer()))
    }

    /** All platform IDs currently on the server, used to purge cached platforms deleted server-side. */
    fun getPlatformIdentifiers(): List<Int> {
        val request = Request.Builder().url(endpoint("/api/platforms/identifiers")).get().build()
        return execute(request, ListSerializer(Int.serializer()))
    }

    fun getFirmware(platformId: Int): List<RommFirmware> {
        val url = endpoint("/api/firmware").newBuilder()
            .addQueryParameter("platform_id", platformId.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        return execute(request, ListSerializer(FirmwareDto.serializer())).map { it.toDomain() }
    }

    fun downloadRom(
        romId: Int,
        fileName: String,
        dest: File,
        isCancelled: () -> Boolean,
        expectedTotal: Long = 0L,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val url = endpoint("/api/roms/$romId/content").newBuilder().addPathSegment(fileName).build()
        val request = Request.Builder().url(url).get().build()
        val response: Response = try {
            clientProvider().newCall(request).execute()
        } catch (e: IOException) {
            throw RommException(null, "Network error: ${e.message}", e)
        }
        response.use {
            if (!it.isSuccessful) throw RommException(it.code, "HTTP ${it.code} downloading rom $romId")
            val body = it.body ?: throw RommException(it.code, "Empty body downloading rom $romId")
            val total = body.contentLength().takeIf { len -> len > 0 } ?: expectedTotal
            dest.parentFile?.mkdirs()
            try {
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            if (isCancelled()) throw RommDownloadCancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            } catch (e: RommDownloadCancelled) {
                dest.delete(); throw e
            } catch (e: IOException) {
                dest.delete(); throw RommException(it.code, "IO error downloading rom $romId: ${e.message}", e)
            }
        }
    }

    fun downloadFirmware(
        firmwareId: Int,
        fileName: String,
        dest: File,
        isCancelled: () -> Boolean,
        expectedTotal: Long = 0L,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val url = endpoint("/api/firmware/$firmwareId/content").newBuilder().addPathSegment(fileName).build()
        val request = Request.Builder().url(url).get().build()
        val response: Response = try {
            clientProvider().newCall(request).execute()
        } catch (e: IOException) {
            throw RommException(null, "Network error: ${e.message}", e)
        }
        response.use {
            if (!it.isSuccessful) throw RommException(it.code, "HTTP ${it.code} downloading firmware $firmwareId")
            val body = it.body ?: throw RommException(it.code, "Empty body downloading firmware $firmwareId")
            val total = body.contentLength().takeIf { len -> len > 0 } ?: expectedTotal
            dest.parentFile?.mkdirs()
            try {
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            if (isCancelled()) throw RommDownloadCancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            } catch (e: RommDownloadCancelled) {
                dest.delete(); throw e
            } catch (e: IOException) {
                dest.delete(); throw RommException(it.code, "IO error downloading firmware $firmwareId: ${e.message}", e)
            }
        }
    }

    fun registerDevice(payload: DeviceRegisterPayload): DeviceRegisterResponse {
        val body = rommJson.encodeToString(DeviceRegisterPayload.serializer(), payload)
            .toRequestBody(jsonMedia)
        val request = Request.Builder().url(endpoint("/api/devices")).post(body).build()
        return execute(request, DeviceRegisterResponse.serializer())
    }

    fun negotiateSync(payload: SyncNegotiatePayload): SyncNegotiateResponse {
        val body = rommJson.encodeToString(SyncNegotiatePayload.serializer(), payload)
            .toRequestBody(jsonMedia)
        val request = Request.Builder().url(endpoint("/api/sync/negotiate")).post(body).build()
        return execute(request, SyncNegotiateResponse.serializer())
    }

    fun getSaves(romId: Int, deviceId: String): List<RommSaveDto> {
        val url = endpoint("/api/saves").newBuilder()
            .addQueryParameter("rom_id", romId.toString())
            .addQueryParameter("device_id", deviceId)
            .build()
        val request = Request.Builder().url(url).get().build()
        return execute(request, ListSerializer(RommSaveDto.serializer()))
    }

    fun uploadSave(romId: Int, emulator: String?, slot: String, deviceId: String, overwrite: Boolean, file: File): RommSaveDto {
        val url = endpoint("/api/saves").newBuilder()
            .addQueryParameter("rom_id", romId.toString())
            .apply { if (emulator != null) addQueryParameter("emulator", emulator) }
            .addQueryParameter("slot", slot)
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("overwrite", overwrite.toString())
            .build()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("saveFile", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(multipart).build()
        return execute(request, RommSaveDto.serializer())
    }

    fun downloadSaveContent(saveId: Int, deviceId: String, dest: File) {
        val url = endpoint("/api/saves/$saveId/content").newBuilder()
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("optimistic", "false")
            .build()
        val request = Request.Builder().url(url).get().build()
        clientProvider().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RommException(response.code, "HTTP ${response.code}: download save $saveId")
            dest.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
    }

    fun confirmSaveDownloaded(saveId: Int, deviceId: String): RommSaveDto {
        val body = rommJson.encodeToString(ConfirmDownloadPayload.serializer(), ConfirmDownloadPayload(deviceId))
            .toRequestBody(jsonMedia)
        val request = Request.Builder().url(endpoint("/api/saves/$saveId/downloaded")).post(body).build()
        return execute(request, RommSaveDto.serializer())
    }

    fun completeSyncSession(sessionId: Int, payload: SyncCompletePayload) {
        val body = rommJson.encodeToString(SyncCompletePayload.serializer(), payload)
            .toRequestBody(jsonMedia)
        val request = Request.Builder().url(endpoint("/api/sync/sessions/$sessionId/complete")).post(body).build()
        clientProvider().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RommException(response.code, "HTTP ${response.code}: complete session $sessionId")
        }
    }

    fun deleteSaves(ids: List<Int>) {
        val body = rommJson.encodeToString(DeleteSavesPayload.serializer(), DeleteSavesPayload(ids))
            .toRequestBody(jsonMedia)
        val request = Request.Builder().url(endpoint("/api/saves/delete")).post(body).build()
        clientProvider().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RommException(response.code, "HTTP ${response.code}: delete saves")
        }
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
