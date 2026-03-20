package dev.cannoli.scorza.romm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class RommClient(
    private var baseUrl: String,
    private var authHeader: String = "",
    private var timeoutMs: Int = 30_000
) {
    fun setAuth(header: String) { authHeader = header }
    fun setBaseUrl(url: String) { baseUrl = url.trimEnd('/') }

    // --- Heartbeat ---

    fun heartbeat(): HeartbeatResponse {
        val json = get(Endpoints.HEARTBEAT)
        val system = json.getJSONObject("SYSTEM")
        return HeartbeatResponse(version = system.getString("VERSION"))
    }

    fun validateConnection(): ConnectionResult {
        return try {
            heartbeat()
            ConnectionResult.OK
        } catch (e: ApiException) {
            when (e.statusCode) {
                401 -> ConnectionResult.UNAUTHORIZED
                403 -> ConnectionResult.FORBIDDEN
                in 500..599 -> ConnectionResult.SERVER_ERROR
                else -> ConnectionResult.ERROR
            }
        } catch (_: Exception) {
            ConnectionResult.ERROR
        }
    }

    // --- Auth ---

    fun exchangeToken(code: String): TokenExchangeResponse {
        val body = JSONObject().put("code", code)
        val json = post(Endpoints.TOKEN_EXCHANGE, body, authenticated = false)
        return TokenExchangeResponse(
            rawToken = json.getString("raw_token"),
            name = json.getString("name"),
            expiresAt = json.optString("expires_at", "")
        )
    }

    fun getCurrentUser(): String {
        val json = get(Endpoints.CURRENT_USER)
        return json.getString("username")
    }

    // --- Platforms ---

    fun getPlatforms(): List<RommPlatform> {
        val arr = getArray(Endpoints.PLATFORMS)
        return (0 until arr.length()).map { parsePlatform(arr.getJSONObject(it)) }
    }

    fun getPlatform(id: Int): RommPlatform {
        val json = get(Endpoints.platformById(id))
        return parsePlatform(json)
    }

    // --- ROMs ---

    fun getRoms(query: GetRomsQuery): PaginatedRoms {
        val params = buildMap {
            if (query.platformId > 0) put("platform_id", query.platformId.toString())
            if (query.collectionId > 0) put("collection_id", query.collectionId.toString())
            if (query.search.isNotEmpty()) put("search", query.search)
            if (query.limit > 0) put("limit", query.limit.toString())
            if (query.offset > 0) put("offset", query.offset.toString())
            if (query.orderBy.isNotEmpty()) put("order_by", query.orderBy)
            if (query.orderDir.isNotEmpty()) put("order_dir", query.orderDir)
        }
        val json = get(Endpoints.ROMS, params)
        val items = json.getJSONArray("items")
        return PaginatedRoms(
            items = (0 until items.length()).map { parseRom(items.getJSONObject(it)) },
            total = json.getInt("total"),
            limit = json.getInt("limit"),
            offset = json.getInt("offset")
        )
    }

    fun getRom(id: Int): RommRom {
        val json = get(Endpoints.romById(id))
        return parseRom(json)
    }

    fun getRomsUpdatedAfter(query: GetRomsQuery, updatedAfter: String): PaginatedRoms {
        val params = buildMap {
            if (query.platformId > 0) put("platform_id", query.platformId.toString())
            if (query.search.isNotEmpty()) put("search", query.search)
            if (query.limit > 0) put("limit", query.limit.toString())
            if (query.offset > 0) put("offset", query.offset.toString())
            if (query.orderBy.isNotEmpty()) put("order_by", query.orderBy)
            if (query.orderDir.isNotEmpty()) put("order_dir", query.orderDir)
            put("updated_after", updatedAfter)
        }
        val json = get(Endpoints.ROMS, params)
        val items = json.getJSONArray("items")
        return PaginatedRoms(
            items = (0 until items.length()).map { parseRom(items.getJSONObject(it)) },
            total = json.getInt("total"),
            limit = json.getInt("limit"),
            offset = json.getInt("offset")
        )
    }

    fun getRomIdentifiers(): List<Int> {
        val arr = getArray("/api/roms/identifiers")
        return (0 until arr.length()).map { arr.getInt(it) }
    }

    fun downloadRom(romId: Int): ByteArray {
        return getRaw("${Endpoints.ROMS_DOWNLOAD}?rom_ids=$romId")
    }

    // --- Saves ---

    fun getSaves(romId: Int, deviceId: String = ""): List<RommSave> {
        val params = buildMap {
            put("rom_id", romId.toString())
            if (deviceId.isNotEmpty()) put("device_id", deviceId)
        }
        val arr = getArray(Endpoints.SAVES, params)
        return (0 until arr.length()).map { parseSave(arr.getJSONObject(it)) }
    }

    fun downloadSave(saveId: Int, deviceId: String): ByteArray {
        val params = if (deviceId.isNotEmpty()) mapOf("device_id" to deviceId) else emptyMap()
        return getRaw(Endpoints.saveContent(saveId), params)
    }

    fun confirmSaveDownloaded(saveId: Int, deviceId: String) {
        val body = JSONObject().put("device_id", deviceId)
        post(Endpoints.saveDownloaded(saveId), body)
    }

    fun uploadSave(romId: Int, savePath: String, emulator: String, deviceId: String, slot: String = ""): RommSave {
        val params = buildMap {
            put("rom_id", romId.toString())
            put("emulator", emulator)
            if (deviceId.isNotEmpty()) put("device_id", deviceId)
            if (slot.isNotEmpty()) put("slot", slot)
        }
        val file = File(savePath)
        val json = postMultipart(Endpoints.SAVES, params, file, "saveFile")
        return parseSave(json)
    }

    fun updateSave(saveId: Int, savePath: String): RommSave {
        val file = File(savePath)
        val json = putMultipart(Endpoints.saveById(saveId), file, "saveFile")
        return parseSave(json)
    }

    // --- Devices ---

    fun registerDevice(name: String, platform: String, clientVersion: String): RommDevice {
        val body = JSONObject()
            .put("name", name)
            .put("platform", platform)
            .put("client", "cannoli")
            .put("client_version", clientVersion)
        val json = post(Endpoints.DEVICES, body)
        return RommDevice(
            id = json.getString("device_id"),
            name = json.getString("name")
        )
    }

    fun getDevices(): List<RommDevice> {
        val arr = getArray(Endpoints.DEVICES)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            RommDevice(id = obj.getString("id"), name = obj.getString("name"))
        }
    }

    // --- Config ---

    fun getPlatformsBinding(): Map<String, String> {
        return try {
            val json = get(Endpoints.CONFIG)
            val binding = json.optJSONObject("PLATFORMS_BINDING") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (key in binding.keys()) {
                result[key] = binding.getString(key)
            }
            result
        } catch (_: Exception) { emptyMap() }
    }

    // --- Firmware ---

    fun getFirmware(platformId: Int): List<RommFirmware> {
        val params = mapOf("platform_id" to platformId.toString())
        val arr = getArray(Endpoints.FIRMWARE, params)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            RommFirmware(
                id = obj.getInt("id"),
                fileName = obj.getString("file_name"),
                fileSizeBytes = obj.getLong("file_size_bytes"),
                md5Hash = obj.optString("md5_hash", ""),
                downloadUrl = "${Endpoints.FIRMWARE}/${obj.getInt("id")}/content/${obj.getString("file_name")}"
            )
        }
    }

    fun downloadFirmware(downloadUrl: String): ByteArray = getRaw(downloadUrl)

    // --- HTTP helpers ---

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val body = request("GET", path, params)
        return JSONObject(body)
    }

    private fun getArray(path: String, params: Map<String, String> = emptyMap()): JSONArray {
        val body = request("GET", path, params)
        return JSONArray(body)
    }

    private fun getRaw(path: String, params: Map<String, String> = emptyMap()): ByteArray {
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        } else ""
        val url = URL("$baseUrl$path$query")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs * 4
            if (authHeader.isNotEmpty()) setRequestProperty("Authorization", authHeader)
        }
        try {
            checkResponse(conn)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject, authenticated: Boolean = true): JSONObject {
        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            if (authenticated && authHeader.isNotEmpty()) setRequestProperty("Authorization", authHeader)
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            checkResponse(conn)
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    private fun postMultipart(
        path: String,
        params: Map<String, String>,
        file: File,
        fieldName: String
    ): JSONObject {
        val boundary = "----CannoliBoundary${System.currentTimeMillis()}"
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        } else ""
        val url = URL("$baseUrl$path$query")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs * 4
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (authHeader.isNotEmpty()) setRequestProperty("Authorization", authHeader)
            doOutput = true
        }
        try {
            conn.outputStream.use { out ->
                writeMultipartFile(out, boundary, fieldName, file)
            }
            checkResponse(conn)
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    private fun putMultipart(path: String, file: File, fieldName: String): JSONObject {
        val boundary = "----CannoliBoundary${System.currentTimeMillis()}"
        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs * 4
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (authHeader.isNotEmpty()) setRequestProperty("Authorization", authHeader)
            doOutput = true
        }
        try {
            conn.outputStream.use { out ->
                writeMultipartFile(out, boundary, fieldName, file)
            }
            checkResponse(conn)
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, path: String, params: Map<String, String> = emptyMap()): String {
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        } else ""
        val url = URL("$baseUrl$path$query")
        android.util.Log.w("RommClient", "$method $url")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (authHeader.isNotEmpty()) setRequestProperty("Authorization", authHeader)
        }
        try {
            checkResponse(conn)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeMultipartFile(out: java.io.OutputStream, boundary: String, fieldName: String, file: File) {
        val crlf = "\r\n"
        out.write("--$boundary$crlf".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$crlf".toByteArray())
        out.write("Content-Type: application/octet-stream$crlf$crlf".toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.write("$crlf--$boundary--$crlf".toByteArray())
    }

    private fun checkResponse(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val errorBody = try {
            conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (_: Exception) { "" }
        throw ApiException(code, errorBody)
    }

    // --- Parsers ---

    private fun parsePlatform(obj: JSONObject) = RommPlatform(
        id = obj.getInt("id"),
        slug = obj.optString("slug", ""),
        fsSlug = obj.optString("fs_slug", ""),
        name = obj.optString("name", ""),
        customName = obj.optString("custom_name", ""),
        romCount = obj.optInt("rom_count", 0),
        firmwareCount = obj.optInt("firmware_count", 0)
    )

    private fun parseRom(obj: JSONObject) = RommRom(
        id = obj.getInt("id"),
        platformId = obj.optInt("platform_id", 0),
        platformFsSlug = obj.optString("platform_fs_slug", ""),
        platformDisplayName = obj.optString("platform_display_name", ""),
        name = obj.optString("name", ""),
        fsName = obj.optString("fs_name", ""),
        fsNameNoExt = obj.optString("fs_name_no_ext", ""),
        fsExtension = obj.optString("fs_extension", ""),
        fsSizeBytes = obj.optLong("fs_size_bytes", 0),
        summary = obj.optString("summary", ""),
        pathCoverSmall = obj.optString("path_cover_small", ""),
        pathCoverLarge = obj.optString("path_cover_large", ""),
        urlCover = obj.optString("url_cover", ""),
        hasMultipleFiles = obj.optBoolean("has_multiple_files", false),
        files = obj.optJSONArray("files")?.let { arr ->
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                RomFile(
                    id = f.getInt("id"),
                    fileName = f.getString("file_name"),
                    fileSizeBytes = f.optLong("file_size_bytes", 0)
                )
            }
        } ?: emptyList()
    )

    private fun parseSave(obj: JSONObject) = RommSave(
        id = obj.getInt("id"),
        romId = obj.getInt("rom_id"),
        fileName = obj.optString("file_name", ""),
        fileNameNoExt = obj.optString("file_name_no_ext", ""),
        fileExtension = obj.optString("file_extension", ""),
        fileSizeBytes = obj.optLong("file_size_bytes", 0),
        downloadPath = obj.optString("download_path", ""),
        emulator = obj.optString("emulator", ""),
        updatedAt = obj.optString("updated_at", ""),
        slot = obj.optString("slot", null),
        deviceSyncs = obj.optJSONArray("device_syncs")?.let { arr ->
            (0 until arr.length()).map { i ->
                val ds = arr.getJSONObject(i)
                DeviceSaveSync(
                    deviceId = ds.getString("device_id"),
                    deviceName = ds.optString("device_name", ""),
                    lastSyncedAt = ds.optString("last_synced_at", ""),
                    isCurrent = ds.optBoolean("is_current", false)
                )
            }
        } ?: emptyList()
    )

    companion object {
        fun basicAuthHeader(username: String, password: String): String {
            val encoded = android.util.Base64.encodeToString(
                "$username:$password".toByteArray(),
                android.util.Base64.NO_WRAP
            )
            return "Basic $encoded"
        }

        fun bearerAuthHeader(token: String): String = "Bearer $token"
    }
}
