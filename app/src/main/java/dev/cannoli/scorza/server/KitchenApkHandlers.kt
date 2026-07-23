package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

internal fun KitchenHttpServer.handleApk(
    method: String,
    segments: List<String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    val installs = apkInstalls ?: return errorResponse(503, "install not available")
    return when {
        method == "POST" && segments.isEmpty() -> handleApkUpload(installs, session)
        method == "GET" && segments.size == 1 -> {
            val status = installs.status(segments[0]) ?: return errorResponse(404, "not found")
            jsonResponse(200, ApkStatusResponse.serializer(), ApkStatusResponse(status.status, status.message))
        }
        else -> errorResponse(405, "method not allowed")
    }
}

private fun KitchenHttpServer.handleApkUpload(
    installs: ApkInstalls,
    session: NanoHTTPD.IHTTPSession,
): Response {
    val contentType = session.headers["content-type"] ?: ""
    if (!contentType.startsWith("multipart/form-data")) {
        return errorResponse(400, "multipart upload required")
    }
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    val staging = installs.stagingDir
    val written = try {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { name ->
            File(staging, sanitizeFilename(name))
        }
    } catch (e: Exception) {
        dev.cannoli.scorza.util.KitchenLog.logError("apk upload failed", e)
        return errorResponse(500, "upload failed")
    }
    val apkName = written.firstOrNull { it.lowercase().endsWith(".apk") }
    if (apkName == null) {
        written.forEach { File(staging, it).delete() }
        return errorResponse(400, "apk file required")
    }
    written.filter { it != apkName }.forEach { File(staging, it).delete() }
    val installId = try {
        installs.begin(File(staging, apkName))
    } catch (e: Exception) {
        dev.cannoli.scorza.util.KitchenLog.logError("apk install begin failed", e)
        return errorResponse(500, "install failed to start")
    }
    return jsonResponse(200, ApkUploadResponse.serializer(), ApkUploadResponse(installId = installId))
}
