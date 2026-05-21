package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

internal fun KitchenHttpServer.handleTags(): Response {
    val romsDir = romsRootProvider()
    val tags = romsDir.listFiles { f -> f.isDirectory }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
    val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
    return jsonResponse(200, """{"tags":[$json]}""")
}

internal fun KitchenHttpServer.handleList(dir: File, displayPath: String, recursive: Boolean = false): Response {
    if (!isSecure(dir)) {
        return jsonResponse(403, """{"error":"forbidden"}""")
    }
    if (!dir.exists() || !dir.isDirectory) {
        return jsonResponse(404, """{"error":"not found"}""")
    }
    val items = if (recursive) {
        val dirPath = dir.toPath()
        val files = mutableListOf<Pair<String, File>>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (!isSecure(child)) continue
                if (child.isDirectory) stack.add(child)
                else files.add(dirPath.relativize(child.toPath()).toString() to child)
            }
        }
        files.sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
            .joinToString(",") { (relativePath, f) ->
                """{"name":"${escapeJson(relativePath)}","type":"file","size":${f.length()}}"""
            }
    } else {
        val entries = dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
            ?: emptyList()
        entries.joinToString(",") { f ->
            val name = escapeJson(f.name)
            val type = if (f.isDirectory) "dir" else "file"
            val size = if (f.isFile) f.length() else 0
            """{"name":"$name","type":"$type","size":$size}"""
        }
    }
    return jsonResponse(200, """{"path":"${escapeJson(displayPath)}","entries":[$items]}""")
}

internal fun KitchenHttpServer.handleMkdir(dir: File): Response {
    if (!isSecure(dir)) {
        return jsonResponse(403, """{"error":"forbidden"}""")
    }
    return if (dir.exists()) {
        jsonResponse(200, """{"ok":true,"existed":true}""")
    } else if (dir.mkdirs()) {
        jsonResponse(201, """{"ok":true}""")
    } else {
        jsonResponse(500, """{"error":"mkdir failed"}""")
    }
}

internal fun KitchenHttpServer.handleDelete(dir: File, filename: String): Response {
    val file = File(dir, filename)
    if (!isSecure(file)) {
        return jsonResponse(403, """{"error":"forbidden"}""")
    }
    if (!file.exists()) {
        return jsonResponse(404, """{"error":"not found"}""")
    }
    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (ok) {
        jsonResponse(200, """{"ok":true}""")
    } else {
        jsonResponse(500, """{"error":"delete failed"}""")
    }
}

internal fun KitchenHttpServer.handleMove(resourceRoot: File, subpath: String, body: String): Response {
    val to = try {
        org.json.JSONObject(body).optString("to", "")
    } catch (_: Exception) { "" }
    if (to.isEmpty()) {
        return jsonResponse(400, """{"error":"missing 'to' field"}""")
    }

    val srcRoot = subpath.substringBefore("/", "")
    val dstRoot = to.substringBefore("/", "")
    if (srcRoot.isEmpty() || dstRoot.isEmpty() || !srcRoot.equals(dstRoot, ignoreCase = true)) {
        return jsonResponse(403, """{"error":"moves must stay within the same subdirectory"}""")
    }

    val src = File(resourceRoot, subpath)
    val dst = File(resourceRoot, to)

    if (!isSecure(src) || !isSecure(dst)) {
        return jsonResponse(403, """{"error":"forbidden"}""")
    }
    if (!src.exists()) {
        return jsonResponse(404, """{"error":"source not found"}""")
    }
    if (dst.exists()) {
        return jsonResponse(409, """{"error":"destination already exists"}""")
    }

    dst.parentFile?.mkdirs()
    return if (src.renameTo(dst)) {
        jsonResponse(200, """{"ok":true}""")
    } else {
        jsonResponse(500, """{"error":"move failed"}""")
    }
}

internal fun KitchenHttpServer.handleUpload(destDir: File, session: NanoHTTPD.IHTTPSession): Response {
    if (!isSecure(destDir)) return jsonResponse(403, """{"error":"forbidden"}""")
    destDir.mkdirs()
    val contentType = session.headers["content-type"] ?: ""
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return jsonResponse(400, """{"error":"chunked upload not supported"}""")
    }
    if (!contentType.startsWith("multipart/form-data")) {
        return jsonResponse(400, """{"error":"multipart upload required"}""")
    }
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    val written = try {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { name ->
            File(destDir, sanitizeFilename(name))
        }
    } catch (e: Exception) {
        dev.cannoli.scorza.util.KitchenLog.logError("upload failed", e)
        return jsonResponse(500, """{"error":"upload failed"}""")
    }
    val files = written.joinToString(",") { "\"${escapeJson(it)}\"" }
    return jsonResponse(200, """{"ok":true,"files":[$files]}""")
}

internal fun KitchenHttpServer.handleArtwork(segments: List<String>): Response {
    val artDir = File(cannoliRoot, "Art")
    when (segments.size) {
        0 -> {
            val tags = artDir.listFiles { f -> f.isDirectory }
                ?.filter { dir -> dir.listFiles { f -> f.isFile }?.isNotEmpty() == true }
                ?.map { it.name }?.sorted() ?: emptyList()
            val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
            return jsonResponse(200, """{"platforms":[$json]}""")
        }
        1 -> {
            val platformDir = File(artDir, segments[0])
            if (!isSecure(platformDir) || !platformDir.isDirectory) {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            val files = platformDir.listFiles { f -> f.isFile }
                ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
            val items = files.joinToString(",") { f ->
                """{"name":"${escapeJson(f.nameWithoutExtension)}","file":"${escapeJson(f.name)}","size":${f.length()}}"""
            }
            return jsonResponse(200, """{"platform":"${escapeJson(segments[0])}","art":[$items]}""")
        }
        2 -> {
            val platformDir = File(artDir, segments[0])
            if (!isSecure(platformDir) || !platformDir.isDirectory) {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            val gameName = segments[1]
            val artFile = platformDir.listFiles { f -> f.isFile && f.nameWithoutExtension == gameName }
                ?.firstOrNull()
            if (artFile == null || !isSecure(artFile)) {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            return fileResponse(artFile, artMime(artFile))
        }
        else -> return jsonResponse(404, """{"error":"not found"}""")
    }
}
