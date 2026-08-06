package dev.cannoli.scorza.server

import android.content.res.AssetManager
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.File

class KitchenHttpServer internal constructor(
    internal val cannoliRoot: File,
    private val assets: AssetManager,
    internal val romsRootProvider: () -> File = { File(cannoliRoot, "Roms") },
    private val port: Int = 1091,
    @Volatile var codeBypass: Boolean = false,
    val pin: String,
    internal val romsRepository: dev.cannoli.scorza.db.RomsRepository? = null,
    internal val scanPlatform: ((String) -> Unit)? = null,
    internal val romDirectoryWalker: dev.cannoli.scorza.util.RomDirectoryWalker? = null,
    internal val atomicRename: dev.cannoli.scorza.util.AtomicRename? = null,
    internal val isArcadePlatform: (String) -> Boolean = { false },
    internal val platformTagsProvider: () -> Collection<String> = { emptyList() },
    internal val volumesProvider: () -> List<KitchenVolume> = { emptyList() },
    internal val apkInstalls: ApkInstalls? = null,
    internal val appsRepository: dev.cannoli.scorza.db.AppsRepository? = null,
    internal val settingsProvider: () -> SettingsResponse = { SettingsResponse("Tools", "Ports") },
    internal val artThumbnails: ArtThumbnails? = null,
) : NanoHTTPD(port) {

    private val socketTimeoutMs = 30_000

    private val socketByHandler = java.util.concurrent.ConcurrentHashMap<ClientHandler, java.net.Socket>()
    private val requestSocket = ThreadLocal<java.net.Socket?>()

    override fun createClientHandler(finalAccept: java.net.Socket, inputStream: java.io.InputStream): ClientHandler {
        val handler = super.createClientHandler(finalAccept, inputStream)
        socketByHandler[handler] = finalAccept
        return handler
    }

    /** Best-effort check that the client is still there. A peer that has gone away leaves the
     *  socket readable at EOF; nothing more is expected on the wire because every response sets
     *  Connection: close, so consuming here cannot eat a pipelined request. */
    internal fun clientGone(): Boolean {
        val socket = requestSocket.get() ?: return false
        if (socket.isClosed) return true
        return try {
            val previous = socket.soTimeout
            socket.soTimeout = 1
            try {
                socket.getInputStream().read() == -1
            } catch (_: java.net.SocketTimeoutException) {
                false
            } finally {
                socket.soTimeout = previous
            }
        } catch (_: Throwable) { false }
    }

    fun startServer() {
        dev.cannoli.scorza.util.KitchenLog.log("starting on port $port")
        setAsyncRunner(
            KitchenAsyncRunner(
                onExec = { handler -> requestSocket.set(socketByHandler.remove(handler)) },
                onDone = { requestSocket.remove() },
            )
        )
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
        } catch (_: RequestAbandonedException) {
            errorResponse(499, "client disconnected")
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

    /** Falls back to null, and so to the original file, whenever a thumbnail is unavailable or not
     *  worth making. Art must still be served if scaling fails. */
    private fun thumbnailFor(source: File, widthParam: String?): File? {
        val width = widthParam?.toIntOrNull() ?: return null
        return artThumbnails?.thumbnail(source, width)
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
            method == "GET" && resource == "apps" -> handleApps()
            method == "GET" && resource == "settings" -> handleSettings()
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
                val targetDir = when {
                    subpath.isEmpty() -> resourceRoot
                    resource == "roms" -> romsTarget(resourceRoot, apiSegments.drop(1))
                    else -> File(resourceRoot, subpath)
                }
                val response = when (method) {
                    "GET" -> {
                        if (targetDir.isFile && isSecure(targetDir)) {
                            val thumb = if (resource == "art") thumbnailFor(targetDir, query["w"]) else null
                            val response = if (thumb != null) fileResponse(thumb, "image/webp")
                            else fileResponse(targetDir, mimeForPath(targetDir.name))
                            // Safe to pin only because the caller passed a version token, so any
                            // change to the art produces a different url rather than a stale hit.
                            if (resource == "art" && !query["v"].isNullOrBlank()) {
                                response.addHeader("Cache-Control", "public, max-age=31536000, immutable")
                            }
                            response
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

    /** Browsing is the one resource rooted in the user's own ROM directory rather than a directory
     *  Cannoli laid out itself, so the canonical tag in the url has to be matched back to the
     *  folder that is really on disk. The other resource dirs are created with canonical names.
     *  Falling back to the tag keeps uploads and mkdir working for a platform with no folder yet. */
    private fun romsTarget(romsRoot: File, segments: List<String>): File {
        val tagDir = romDirectoryWalker?.resolveTagDir(segments[0]) ?: File(romsRoot, segments[0])
        val rest = segments.drop(1)
        return if (rest.isEmpty()) tagDir else File(tagDir, rest.joinToString(File.separator))
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

    /** Every platform tag Cannoli recognises. The database is the usual source, but a platform only
     *  gains a row once it has been scanned, so the configured tags are folded in to keep a folder
     *  for a never-scanned platform both visible and reachable. */
    internal fun allPlatformTags(): List<String> =
        (romsRepository?.knownPlatformTags().orEmpty() + platformTagsProvider()).distinct()

    /** A ROM directory the launcher did not scaffold keeps whatever folder names the user already
     *  had, and the launcher resolves those case-insensitively. Every tag entering the api is
     *  matched the same way and answered in the one spelling the database uses, so the frontend's
     *  display names, icons and grouping key off the same string the games endpoint accepts. */
    internal fun canonicalTag(raw: String): String? =
        allPlatformTags().firstOrNull { it.equals(raw, ignoreCase = true) }

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
        // The dashboard appends these itself from /api/apps, so listing them as platforms too
        // would put a second, duplicate tile on screen.
        internal val RESERVED_APP_TAGS = setOf("TOOLS", "PORTS")

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
