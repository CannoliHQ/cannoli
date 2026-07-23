package dev.cannoli.scorza.server

import android.content.res.AssetManager
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.File

class KitchenHttpServer(
    internal val cannoliRoot: File,
    private val assets: AssetManager,
    internal val romsRootProvider: () -> File = { File(cannoliRoot, "Roms") },
    private val port: Int = 1091,
    @Volatile var codeBypass: Boolean = false,
    val pin: String,
    internal val romsRepository: dev.cannoli.scorza.db.RomsRepository? = null,
    internal val scanPlatform: ((String) -> Unit)? = null,
    internal val romDirectoryWalker: dev.cannoli.scorza.util.RomDirectoryWalker? = null,
    internal val volumesProvider: () -> List<KitchenVolume> = { emptyList() },
    internal val apkInstalls: ApkInstalls? = null,
) : NanoHTTPD(port) {

    private val socketTimeoutMs = 30_000

    fun startServer() {
        dev.cannoli.scorza.util.KitchenLog.log("starting on port $port")
        start(socketTimeoutMs, false)
        dev.cannoli.scorza.util.KitchenLog.log("started on port $listeningPort")
    }

    fun stopServer() {
        stop()
        dev.cannoli.scorza.util.KitchenLog.log("stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val started = System.currentTimeMillis()
        val response = try {
            dispatch(session)
        } catch (e: Exception) {
            dev.cannoli.scorza.util.KitchenLog.logError("request failed", e)
            errorResponse(500, "internal")
        }
        dev.cannoli.scorza.util.KitchenLog.log(
            "${session.remoteIpAddress} ${session.method} ${session.uri} " +
                "-> ${response.status.requestStatus} (${System.currentTimeMillis() - started}ms)"
        )
        return response
    }

    internal fun decorate(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        r.addHeader("Connection", "close")
        r.setKeepAlive(false)
        return r
    }

    internal fun status(code: Int): Response.IStatus =
        Response.Status.values().firstOrNull { it.requestStatus == code }
            ?: object : Response.IStatus {
                override fun getRequestStatus() = code
                override fun getDescription() = code.toString()
            }

    internal fun jsonResponse(code: Int, json: String): Response =
        decorate(newFixedLengthResponse(status(code), "application/json", json))

    internal fun corsResponse(code: Int, contentType: String, body: ByteArray): Response =
        decorate(
            newFixedLengthResponse(
                status(code), contentType,
                java.io.ByteArrayInputStream(body), body.size.toLong()
            )
        )

    internal fun fileResponse(file: File, contentType: String): Response =
        decorate(
            newFixedLengthResponse(
                Response.Status.OK, contentType,
                file.inputStream(), file.length()
            )
        )

    private fun dispatch(session: IHTTPSession): Response {
        val method = session.method.name
        val rawPath = session.uri
        val query: Map<String, String> = session.parameters
            .mapValues { it.value.firstOrNull() ?: "" }
        val headers: Map<String, String> = session.headers

        if (method == "OPTIONS") return corsResponse(204, "text/plain", ByteArray(0))

        // NanoHTTPD already percent-decodes session.uri; decoding again corrupts + and % in names
        val segments = rawPath.removePrefix("/").split("/")

        if (segments.firstOrNull() != "api") {
            return if (method == "GET") serveStatic(rawPath)
            else errorResponse(404, "not found")
        }
        val apiSegments = segments.drop(1)
        val resource = apiSegments.firstOrNull() ?: ""

        if (method == "GET" && resource == "auth") return handleAuthStatus()
        if (!checkAuth(headers)) return errorResponse(401, "unauthorized")

        return route(method, resource, apiSegments, query, headers, session)
    }

    private fun route(
        method: String,
        resource: String,
        apiSegments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String>,
        session: IHTTPSession,
    ): Response {
        return when {
            method == "GET" && resource == "info" -> handleInfo()
            method == "GET" && resource == "tags" -> handleTags()
            resource == "games" -> {
                val gameSegments = apiSegments.drop(1)
                handleGames(method, gameSegments, query, headers, session)
            }
            resource == "scan" -> {
                if (method != "POST") return errorResponse(405, "method not allowed")
                val tag = apiSegments.getOrNull(1)
                if (tag.isNullOrBlank()) return errorResponse(400, "platform required")
                val scan = scanPlatform
                    ?: return errorResponse(503, "scan not available")
                scan(tag)
                okResponse()
            }
            resource == "slots" -> {
                val slotSegments = apiSegments.drop(1)
                handleSlots(method, slotSegments, query, headers, session)
            }
            resource == "artwork" -> {
                if (method != "GET") return errorResponse(405, "method not allowed")
                val artSegments = apiSegments.drop(1)
                handleArtwork(artSegments)
            }
            resource == "fs" -> handleFs(method, apiSegments.drop(1), query, session)
            resource == "apk" -> handleApk(method, apiSegments.drop(1), session)
            resource in RESOURCE_DIRS -> {
                val baseDir = RESOURCE_DIRS[resource]!!
                val subpath = apiSegments.drop(1).joinToString("/")
                val displayPath = if (subpath.isEmpty()) baseDir else "$baseDir/$subpath"
                val resourceRoot = if (resource == "roms") romsRootProvider() else File(cannoliRoot, baseDir)
                val targetDir = if (subpath.isEmpty()) resourceRoot else File(resourceRoot, subpath)
                val response = when (method) {
                    "GET" -> {
                        if (targetDir.isFile && isSecure(targetDir)) {
                            fileResponse(targetDir, mimeForPath(targetDir.name))
                        } else {
                            handleList(targetDir, displayPath, query["recursive"] == "true")
                        }
                    }
                    "POST" -> handleUpload(targetDir, session)
                    "PUT" -> {
                        if (subpath.isEmpty()) {
                            errorResponse(400, "path required")
                        } else {
                            handleMkdir(targetDir)
                        }
                    }
                    "DELETE" -> {
                        if (subpath.isEmpty()) {
                            errorResponse(400, "path required")
                        } else {
                            handleDelete(targetDir.parentFile ?: targetDir, targetDir.name)
                        }
                    }
                    "PATCH" -> {
                        if (subpath.isEmpty()) {
                            errorResponse(400, "path required")
                        } else {
                            val body = readBody(session)
                            handleMove(resourceRoot, subpath, body)
                        }
                    }
                    else -> errorResponse(405, "method not allowed")
                }
                if (resource == "roms" && method in setOf("POST", "PUT", "DELETE", "PATCH")) {
                    val tag = apiSegments.getOrNull(1)
                    if (!tag.isNullOrBlank() && response.status.requestStatus in 200..299) {
                        scanPlatform?.invoke(tag)
                    }
                }
                response
            }
            else -> errorResponse(404, "not found")
        }
    }

    internal fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ""
        val input = session.inputStream
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        return String(bytes, 0, read)
    }

    private fun handleInfo(): Response =
        jsonResponse(200, InfoResponse.serializer(), InfoResponse("Cannoli Kitchen", 1))

    private fun serveStatic(endpoint: String): Response {
        val path = if (endpoint == "/") "index.html" else endpoint.removePrefix("/")
        if (path.contains("..")) return corsResponse(403, "text/plain", "forbidden".toByteArray())
        return try {
            corsResponse(200, mimeForPath(path), assets.open("kitchen/$path").readBytes())
        } catch (_: Exception) {
            try {
                corsResponse(200, "text/html", assets.open("kitchen/index.html").readBytes())
            } catch (_: Exception) {
                corsResponse(404, "text/plain", "not found".toByteArray())
            }
        }
    }

    private fun checkAuth(headers: Map<String, String>): Boolean {
        if (codeBypass) return true
        val auth = headers["authorization"] ?: return false
        if (!auth.startsWith("Basic ")) return false
        val decoded = try {
            String(Base64.decode(auth.removePrefix("Basic "), Base64.NO_WRAP))
        } catch (_: Exception) { return false }
        val parts = decoded.split(":", limit = 2)
        return parts.size == 2 && parts[0] == "nonna" && parts[1] == pin
    }

    private fun handleAuthStatus(): Response =
        jsonResponse(200, AuthStatusResponse.serializer(), AuthStatusResponse(required = !codeBypass))

    internal fun defaultRoots(): List<File> {
        val roms = try { romsRootProvider() } catch (_: Exception) { null }
        return listOfNotNull(cannoliRoot, roms)
    }

    internal fun isSecure(file: File): Boolean = isSecure(file, defaultRoots())

    internal fun isSecure(file: File, roots: List<File>): Boolean {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        val canonical = file.canonicalPath
        return roots.any { root ->
            val rootCanonical = try { root.canonicalPath } catch (_: Exception) { return@any false }
            canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
        }
    }

    internal fun sanitizeFilename(name: String): String {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
            .replace(Regex("[/\\\\]"), "_").trim()
    }

    internal fun mimeForPath(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".bmp") -> "image/bmp"
        path.endsWith(".ico") -> "image/x-icon"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        else -> "application/octet-stream"
    }

    companion object {
        private val RESOURCE_DIRS = mapOf(
            "roms" to "Roms",
            "art" to "Art",
            "overlays" to "Overlays",
            "saves" to "Saves",
            "states" to "Save States",
            "bios" to "BIOS",
            "wallpapers" to "Wallpapers",
            "guides" to "Guides",
            "cheats" to "Cheats",
            "shaders" to "Shaders"
        )
    }
}
