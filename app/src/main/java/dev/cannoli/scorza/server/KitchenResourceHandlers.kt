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
    return jsonResponse(200, TagsResponse.serializer(), TagsResponse(tags))
}

internal fun KitchenHttpServer.handleList(dir: File, displayPath: String, recursive: Boolean = false): Response {
    if (!isSecure(dir)) {
        return errorResponse(403, "forbidden")
    }
    if (!dir.exists() || !dir.isDirectory) {
        return jsonResponse(200, DirListResponse.serializer(), DirListResponse(displayPath, emptyList()))
    }
    val entries: List<DirEntry> = if (recursive) {
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
            .map { (relativePath, f) -> DirEntry(relativePath, "file", f.length()) }
    } else {
        dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
            ?.map { f ->
                DirEntry(
                    name = f.name,
                    type = if (f.isDirectory) "dir" else "file",
                    size = if (f.isFile) f.length() else 0L,
                )
            } ?: emptyList()
    }
    return jsonResponse(200, DirListResponse.serializer(), DirListResponse(displayPath, entries))
}

internal fun KitchenHttpServer.handleMkdir(dir: File): Response {
    if (!isSecure(dir)) {
        return errorResponse(403, "forbidden")
    }
    return if (dir.exists()) {
        jsonResponse(200, OkResponse.serializer(), OkResponse(ok = true, existed = true))
    } else if (dir.mkdirs()) {
        okResponse(201)
    } else {
        errorResponse(500, "mkdir failed")
    }
}

internal fun KitchenHttpServer.handleDelete(dir: File, filename: String): Response {
    val file = File(dir, filename)
    if (!isSecure(file)) {
        return errorResponse(403, "forbidden")
    }
    if (!file.exists()) {
        return errorResponse(404, "not found")
    }
    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (ok) {
        okResponse()
    } else {
        errorResponse(500, "delete failed")
    }
}

internal fun KitchenHttpServer.handleMove(resourceRoot: File, subpath: String, body: String): Response {
    val to = try {
        org.json.JSONObject(body).optString("to", "")
    } catch (_: Exception) { "" }
    if (to.isEmpty()) {
        return errorResponse(400, "missing 'to' field")
    }

    val srcRoot = subpath.substringBefore("/", "")
    val dstRoot = to.substringBefore("/", "")
    if (srcRoot.isEmpty() || dstRoot.isEmpty() || !srcRoot.equals(dstRoot, ignoreCase = true)) {
        return errorResponse(403, "moves must stay within the same subdirectory")
    }

    val src = File(resourceRoot, subpath)
    val dst = File(resourceRoot, to)

    if (!isSecure(src) || !isSecure(dst)) {
        return errorResponse(403, "forbidden")
    }
    if (!src.exists()) {
        return errorResponse(404, "source not found")
    }
    if (dst.exists()) {
        return errorResponse(409, "destination already exists")
    }

    dst.parentFile?.mkdirs()
    return if (src.renameTo(dst)) {
        okResponse()
    } else {
        errorResponse(500, "move failed")
    }
}

internal fun KitchenHttpServer.handleUpload(destDir: File, session: NanoHTTPD.IHTTPSession): Response {
    if (!isSecure(destDir)) return errorResponse(403, "forbidden")
    destDir.mkdirs()
    val contentType = session.headers["content-type"] ?: ""
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return errorResponse(400, "chunked upload not supported")
    }
    if (!contentType.startsWith("multipart/form-data")) {
        return errorResponse(400, "multipart upload required")
    }
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    val written = try {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { name ->
            File(destDir, sanitizeFilename(name))
        }
    } catch (e: Exception) {
        dev.cannoli.scorza.util.KitchenLog.logError("upload failed", e)
        return errorResponse(500, "upload failed")
    }
    return jsonResponse(200, UploadResponse.serializer(), UploadResponse(files = written))
}

internal fun KitchenHttpServer.handleArtwork(segments: List<String>): Response {
    val artDir = File(cannoliRoot, "Art")
    when (segments.size) {
        0 -> {
            val tags = artDir.listFiles { f -> f.isDirectory }
                ?.filter { dir -> dir.listFiles { f -> f.isFile }?.isNotEmpty() == true }
                ?.map { it.name }?.sorted() ?: emptyList()
            return jsonResponse(200, PlatformsResponse.serializer(), PlatformsResponse(tags))
        }
        1 -> {
            val platformDir = File(artDir, segments[0])
            if (!isSecure(platformDir) || !platformDir.isDirectory) {
                return errorResponse(404, "not found")
            }
            val files = platformDir.listFiles { f -> f.isFile }
                ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
            val items = files.map { f ->
                ArtEntry(name = f.nameWithoutExtension, file = f.name, size = f.length())
            }
            return jsonResponse(200, ArtListResponse.serializer(), ArtListResponse(segments[0], items))
        }
        2 -> {
            val platformDir = File(artDir, segments[0])
            if (!isSecure(platformDir) || !platformDir.isDirectory) {
                return errorResponse(404, "not found")
            }
            val gameName = segments[1]
            val artFile = platformDir.listFiles { f -> f.isFile && f.nameWithoutExtension == gameName }
                ?.firstOrNull()
            if (artFile == null || !isSecure(artFile)) {
                return errorResponse(404, "not found")
            }
            return fileResponse(artFile, artMime(artFile))
        }
        else -> return errorResponse(404, "not found")
    }
}
