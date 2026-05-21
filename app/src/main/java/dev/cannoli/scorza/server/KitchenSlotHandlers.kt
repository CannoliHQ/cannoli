package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

internal fun KitchenHttpServer.handleSlots(
    method: String,
    segments: List<String>,
    query: Map<String, String>,
    headers: Map<String, String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    val statesDir = File(cannoliRoot, "Save States")
    when (segments.size) {
        0 -> {
            if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
            val tags = statesDir.listFiles { f -> f.isDirectory }
                ?.filter { dir -> dir.listFiles { f -> f.isDirectory }?.isNotEmpty() == true }
                ?.map { it.name }?.sorted() ?: emptyList()
            val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
            return jsonResponse(200, """{"platforms":[$json]}""")
        }
        1 -> {
            if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
            val platformDir = File(statesDir, segments[0])
            if (!isSecure(platformDir) || !platformDir.isDirectory) {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            val games = platformDir.listFiles { f -> f.isDirectory }
                ?.map { it.name }?.sorted() ?: emptyList()
            val json = games.joinToString(",") { "\"${escapeJson(it)}\"" }
            return jsonResponse(200, """{"platform":"${escapeJson(segments[0])}","games":[$json]}""")
        }
        2 -> {
            val platformTag = segments[0]
            val romName = java.text.Normalizer.normalize(segments[1], java.text.Normalizer.Form.NFC)
            val gameDir = File(statesDir, "$platformTag/$romName")
            if (!isSecure(gameDir)) return jsonResponse(403, """{"error":"forbidden"}""")
            return when (method) {
                "GET" -> handleSlotsList(gameDir, romName)
                "POST" -> {
                    val slot = query["slot"]?.toIntOrNull()
                    if (slot == null || slot < 0 || slot > 10) {
                        return jsonResponse(400, """{"error":"slot param required (0-10)"}""")
                    }
                    handleSlotUpload(gameDir, romName, slot, session)
                }
                "DELETE" -> {
                    val slot = query["slot"]?.toIntOrNull()
                    if (slot == null || slot < 0 || slot > 10) {
                        return jsonResponse(400, """{"error":"slot param required (0-10)"}""")
                    }
                    handleSlotDelete(gameDir, romName, slot)
                }
                else -> jsonResponse(405, """{"error":"method not allowed"}""")
            }
        }
        3 -> {
            if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
            val platformTag = segments[0]
            val romName = java.text.Normalizer.normalize(segments[1], java.text.Normalizer.Form.NFC)
            val action = segments[2]
            return when (action) {
                "thumbnail" -> {
                    val slot = query["slot"]?.toIntOrNull()
                    if (slot == null || slot < 0 || slot > 10) {
                        return jsonResponse(400, """{"error":"slot param required (0-10)"}""")
                    }
                    val gameDir = File(statesDir, "$platformTag/$romName")
                    val thumbFile = File(gameDir, "${raStateName(romName, slot)}.png")
                    if (!isSecure(thumbFile) || !thumbFile.exists()) {
                        return jsonResponse(404, """{"error":"not found"}""")
                    }
                    fileResponse(thumbFile, "image/png")
                }
                "file" -> {
                    val slot = query["slot"]?.toIntOrNull()
                    if (slot == null || slot < 0 || slot > 10) {
                        return jsonResponse(400, """{"error":"slot param required (0-10)"}""")
                    }
                    val gameDir = File(statesDir, "$platformTag/$romName")
                    val stateFile = File(gameDir, raStateName(romName, slot))
                    if (!isSecure(stateFile) || !stateFile.exists()) {
                        return jsonResponse(404, """{"error":"not found"}""")
                    }
                    fileResponse(stateFile, "application/octet-stream")
                }
                "zip" -> {
                    val gameDir = File(statesDir, "$platformTag/$romName")
                    if (!isSecure(gameDir)) {
                        return jsonResponse(403, """{"error":"forbidden"}""")
                    }
                    val bytes = SlotsZip.build(gameDir, romName)
                        ?: return jsonResponse(404, """{"error":"no states"}""")
                    bytesResponse(200, "application/zip", bytes)
                }
                else -> jsonResponse(404, """{"error":"not found"}""")
            }
        }
        else -> return jsonResponse(404, """{"error":"not found"}""")
    }
}

internal fun KitchenHttpServer.handleSlotsList(gameDir: File, romName: String): Response {
    val slots = (0..10).map { n ->
        val stateFile = File(gameDir, "${raStateName(romName, n)}")
        val thumbFile = File(gameDir, "${raStateName(romName, n)}.png")
        val label = if (n == 0) "Auto" else "Slot ${n - 1}"
        val exists = stateFile.exists()
        val size = if (exists) stateFile.length() else 0
        val modified = if (exists) stateFile.lastModified() else 0
        val hasThumb = thumbFile.exists()
        """{"slot":$n,"label":"$label","exists":$exists,"size":$size,"modified":$modified,"thumbnail":$hasThumb}"""
    }
    return jsonResponse(200, """{"game":"${escapeJson(romName)}","slots":[${slots.joinToString(",")}]}""")
}

internal fun KitchenHttpServer.handleSlotUpload(
    gameDir: File,
    romName: String,
    slot: Int,
    session: NanoHTTPD.IHTTPSession,
): Response {
    if (!isSecure(gameDir)) return jsonResponse(403, """{"error":"forbidden"}""")
    gameDir.mkdirs()
    val destFile = File(gameDir, "${raStateName(romName, slot)}")

    val contentType = session.headers["content-type"] ?: ""
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return jsonResponse(400, """{"error":"chunked upload not supported"}""")
    }
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L

    if (contentType.startsWith("multipart/form-data")) {
        try {
            KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { destFile }
        } catch (e: Exception) {
            dev.cannoli.scorza.util.KitchenLog.logError("upload failed", e)
            return jsonResponse(500, """{"error":"upload failed"}""")
        }
    } else {
        java.io.BufferedOutputStream(destFile.outputStream(), 262144).use { bos ->
            val buf = ByteArray(262144)
            var remaining = contentLength
            val input = session.inputStream
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                bos.write(buf, 0, n)
                remaining -= n
            }
        }
    }

    return jsonResponse(200, """{"ok":true,"slot":$slot,"file":"${escapeJson(destFile.name)}"}""")
}

internal fun KitchenHttpServer.handleSlotDelete(gameDir: File, romName: String, slot: Int): Response {
    val stateFile = File(gameDir, "${raStateName(romName, slot)}")
    val thumbFile = File(gameDir, "${raStateName(romName, slot)}.png")
    if (!isSecure(stateFile)) return jsonResponse(403, """{"error":"forbidden"}""")
    if (!stateFile.exists()) return jsonResponse(404, """{"error":"not found"}""")
    stateFile.delete()
    thumbFile.delete()
    return jsonResponse(200, """{"ok":true}""")
}
