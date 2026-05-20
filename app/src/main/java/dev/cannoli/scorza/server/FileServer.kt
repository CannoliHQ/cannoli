package dev.cannoli.scorza.server

import android.content.res.AssetManager
import android.util.Base64
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class FileServer(
    private val cannoliRoot: File,
    private val assets: AssetManager,
    private val romsRootProvider: () -> File = { File(cannoliRoot, "Roms") },
    private val port: Int = 1091,
    @Volatile var codeBypass: Boolean = false,
    private val romsRepository: dev.cannoli.scorza.db.RomsRepository? = null,
    private val scanPlatform: ((String) -> Unit)? = null,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var threadPool: ExecutorService? = null
    var pin: String = ""
        private set

    fun start() {
        if (running) return
        pin = generatePin()
        running = true
        threadPool = Executors.newFixedThreadPool(4)
        thread(isDaemon = true, name = "FileServer") {
            var socket: ServerSocket? = null
            for (attempt in 1..3) {
                try {
                    socket = ServerSocket(port)
                    break
                } catch (_: java.net.BindException) {
                    if (attempt == 3) { running = false; return@thread }
                    Thread.sleep(500)
                }
            }
            serverSocket = socket!!
            while (running) {
                try {
                    val client = socket.accept()
                    threadPool?.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        threadPool?.shutdownNow()
        threadPool = null
    }

    val isRunning: Boolean get() = running

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 600_000
            client.receiveBufferSize = 262144
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val urlParts = parts[1].split("?", limit = 2)
            val rawPath = urlParts[0]
            val queryString = if (urlParts.size > 1) urlParts[1] else ""
            val queryParams = queryString.split("&")
                .filter { it.contains("=") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                }

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase(java.util.Locale.ROOT)] =
                        line.substring(colonIdx + 1).trim()
                }
            }

            val output = client.getOutputStream()

            if (method == "OPTIONS") {
                sendCors(output, 204, "text/plain", ByteArray(0))
                return
            }

            val segments = rawPath.removePrefix("/").split("/")
                .map { URLDecoder.decode(it, "UTF-8") }

            if (segments.firstOrNull() != "api") {
                if (method == "GET") serveStatic(output, rawPath)
                else sendJson(output, 404, """{"error":"not found"}""")
                return
            }

            val apiSegments = segments.drop(1)
            val resource = apiSegments.firstOrNull() ?: ""

            if (method == "GET" && resource == "auth") {
                handleAuthStatus(output)
                return
            }

            if (!checkAuth(headers)) {
                sendUnauthorized(output)
                return
            }

            when {
                method == "GET" && resource == "info" -> handleInfo(output)
                method == "GET" && resource == "tags" -> handleTags(output)
                resource == "games" -> {
                    val gameSegments = apiSegments.drop(1)
                    handleGames(method, gameSegments, queryParams, headers, input, output)
                }
                resource == "scan" -> {
                    if (method != "POST") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                    val tag = apiSegments.getOrNull(1)
                    if (tag.isNullOrBlank()) { sendJson(output, 400, """{"error":"platform required"}"""); return }
                    val scan = scanPlatform
                    if (scan == null) { sendJson(output, 503, """{"error":"scan not available"}"""); return }
                    scan(tag)
                    sendJson(output, 200, """{"ok":true}""")
                }
                resource == "slots" -> {
                    val slotSegments = apiSegments.drop(1)
                    handleSlots(method, slotSegments, queryParams, headers, input, output)
                }
                resource == "artwork" -> {
                    if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                    val artSegments = apiSegments.drop(1)
                    handleArtwork(artSegments, output)
                }
                resource in RESOURCE_DIRS -> {
                    val baseDir = RESOURCE_DIRS[resource]!!
                    val subpath = apiSegments.drop(1).joinToString("/")
                    val displayPath = if (subpath.isEmpty()) baseDir else "$baseDir/$subpath"
                    val resourceRoot = if (resource == "roms") romsRootProvider() else File(cannoliRoot, baseDir)
                    val targetDir = if (subpath.isEmpty()) resourceRoot else File(resourceRoot, subpath)
                    when (method) {
                        "GET" -> handleList(output, targetDir, displayPath, queryParams["recursive"] == "true")
                        "POST" -> {
                            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                            val contentType = headers["content-type"] ?: ""
                            handleUpload(output, targetDir, contentType, contentLength, input)
                        }
                        "PUT" -> {
                            if (subpath.isEmpty()) {
                                sendJson(output, 400, """{"error":"path required"}""")
                            } else {
                                handleMkdir(output, targetDir)
                            }
                        }
                        "DELETE" -> {
                            if (subpath.isEmpty()) {
                                sendJson(output, 400, """{"error":"path required"}""")
                            } else {
                                handleDelete(output, targetDir.parentFile ?: targetDir, targetDir.name)
                            }
                        }
                        "PATCH" -> {
                            if (subpath.isEmpty()) {
                                sendJson(output, 400, """{"error":"path required"}""")
                            } else {
                                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                                val body = if (contentLength > 0) {
                                    val bytes = ByteArray(contentLength)
                                    var read = 0
                                    while (read < contentLength) {
                                        val n = input.read(bytes, read, contentLength - read)
                                        if (n <= 0) break
                                        read += n
                                    }
                                    String(bytes, 0, read)
                                } else ""
                                handleMove(output, resourceRoot, subpath, body)
                            }
                        }
                        else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                    }
                }
                else -> sendJson(output, 404, """{"error":"not found"}""")
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(output: OutputStream) {
        sendJson(output, 200, """{"name":"Cannoli Kitchen","version":1}""")
    }

    private fun handleTags(output: OutputStream) {
        val romsDir = romsRootProvider()
        val tags = romsDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
        sendJson(output, 200, """{"tags":[$json]}""")
    }

    private fun handleList(output: OutputStream, dir: File, displayPath: String, recursive: Boolean = false) {
        if (!isSecure(dir)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        if (!dir.exists() || !dir.isDirectory) {
            sendJson(output, 404, """{"error":"not found"}""")
            return
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
        sendJson(output, 200, """{"path":"${escapeJson(displayPath)}","entries":[$items]}""")
    }

    private fun handleMkdir(output: OutputStream, dir: File) {
        if (!isSecure(dir)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        if (dir.exists()) {
            sendJson(output, 200, """{"ok":true,"existed":true}""")
        } else if (dir.mkdirs()) {
            sendJson(output, 201, """{"ok":true}""")
        } else {
            sendJson(output, 500, """{"error":"mkdir failed"}""")
        }
    }

    private fun handleDelete(output: OutputStream, dir: File, filename: String) {
        val file = File(dir, filename)
        if (!isSecure(file)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        if (!file.exists()) {
            sendJson(output, 404, """{"error":"not found"}""")
            return
        }
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (ok) {
            sendJson(output, 200, """{"ok":true}""")
        } else {
            sendJson(output, 500, """{"error":"delete failed"}""")
        }
    }

    private fun handleMove(output: OutputStream, resourceRoot: File, subpath: String, body: String) {
        val to = try {
            org.json.JSONObject(body).optString("to", "")
        } catch (_: Exception) { "" }
        if (to.isEmpty()) {
            sendJson(output, 400, """{"error":"missing 'to' field"}""")
            return
        }

        val srcRoot = subpath.substringBefore("/", "")
        val dstRoot = to.substringBefore("/", "")
        if (srcRoot.isEmpty() || dstRoot.isEmpty() || !srcRoot.equals(dstRoot, ignoreCase = true)) {
            sendJson(output, 403, """{"error":"moves must stay within the same subdirectory"}""")
            return
        }

        val src = File(resourceRoot, subpath)
        val dst = File(resourceRoot, to)

        if (!isSecure(src) || !isSecure(dst)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        if (!src.exists()) {
            sendJson(output, 404, """{"error":"source not found"}""")
            return
        }
        if (dst.exists()) {
            sendJson(output, 409, """{"error":"destination already exists"}""")
            return
        }

        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) {
            sendJson(output, 200, """{"ok":true}""")
        } else {
            sendJson(output, 500, """{"error":"move failed"}""")
        }
    }

    private fun handleSlots(
        method: String,
        segments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: java.io.InputStream,
        output: OutputStream
    ) {
        val statesDir = File(cannoliRoot, "Save States")
        when (segments.size) {
            0 -> {
                if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                val tags = statesDir.listFiles { f -> f.isDirectory }
                    ?.filter { dir -> dir.listFiles { f -> f.isDirectory }?.isNotEmpty() == true }
                    ?.map { it.name }?.sorted() ?: emptyList()
                val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
                sendJson(output, 200, """{"platforms":[$json]}""")
            }
            1 -> {
                if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                val platformDir = File(statesDir, segments[0])
                if (!isSecure(platformDir) || !platformDir.isDirectory) {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                val games = platformDir.listFiles { f -> f.isDirectory }
                    ?.map { it.name }?.sorted() ?: emptyList()
                val json = games.joinToString(",") { "\"${escapeJson(it)}\"" }
                sendJson(output, 200, """{"platform":"${escapeJson(segments[0])}","games":[$json]}""")
            }
            2 -> {
                val platformTag = segments[0]
                val romName = java.text.Normalizer.normalize(segments[1], java.text.Normalizer.Form.NFC)
                val gameDir = File(statesDir, "$platformTag/$romName")
                if (!isSecure(gameDir)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                when (method) {
                    "GET" -> handleSlotsList(output, gameDir, romName)
                    "POST" -> {
                        val slot = query["slot"]?.toIntOrNull()
                        if (slot == null || slot < 0 || slot > 10) {
                            sendJson(output, 400, """{"error":"slot param required (0-10)"}"""); return
                        }
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        val contentType = headers["content-type"] ?: ""
                        handleSlotUpload(output, gameDir, romName, slot, contentType, contentLength, input)
                    }
                    "DELETE" -> {
                        val slot = query["slot"]?.toIntOrNull()
                        if (slot == null || slot < 0 || slot > 10) {
                            sendJson(output, 400, """{"error":"slot param required (0-10)"}"""); return
                        }
                        handleSlotDelete(output, gameDir, romName, slot)
                    }
                    else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                }
            }
            3 -> {
                if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                val platformTag = segments[0]
                val romName = java.text.Normalizer.normalize(segments[1], java.text.Normalizer.Form.NFC)
                val action = segments[2]
                when (action) {
                    "thumbnail" -> {
                        val slot = query["slot"]?.toIntOrNull()
                        if (slot == null || slot < 0 || slot > 10) {
                            sendJson(output, 400, """{"error":"slot param required (0-10)"}"""); return
                        }
                        val gameDir = File(statesDir, "$platformTag/$romName")
                        val thumbFile = File(gameDir, "${raStateName(romName, slot)}.png")
                        if (!isSecure(thumbFile) || !thumbFile.exists()) {
                            sendJson(output, 404, """{"error":"not found"}"""); return
                        }
                        sendFile(output, thumbFile, "image/png")
                    }
                    "file" -> {
                        val slot = query["slot"]?.toIntOrNull()
                        if (slot == null || slot < 0 || slot > 10) {
                            sendJson(output, 400, """{"error":"slot param required (0-10)"}"""); return
                        }
                        val gameDir = File(statesDir, "$platformTag/$romName")
                        val stateFile = File(gameDir, raStateName(romName, slot))
                        if (!isSecure(stateFile) || !stateFile.exists()) {
                            sendJson(output, 404, """{"error":"not found"}"""); return
                        }
                        sendFile(output, stateFile, "application/octet-stream")
                    }
                    "zip" -> {
                        val gameDir = File(statesDir, "$platformTag/$romName")
                        if (!isSecure(gameDir)) {
                            sendJson(output, 403, """{"error":"forbidden"}"""); return
                        }
                        val bytes = SlotsZip.build(gameDir, romName)
                        if (bytes == null) {
                            sendJson(output, 404, """{"error":"no states"}"""); return
                        }
                        sendBytes(output, bytes, "application/zip")
                    }
                    else -> sendJson(output, 404, """{"error":"not found"}""")
                }
            }
            else -> sendJson(output, 404, """{"error":"not found"}""")
        }
    }

    private fun handleSlotsList(output: OutputStream, gameDir: File, romName: String) {
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
        sendJson(output, 200, """{"game":"${escapeJson(romName)}","slots":[${slots.joinToString(",")}]}""")
    }

    private fun handleSlotUpload(
        output: OutputStream,
        gameDir: File,
        romName: String,
        slot: Int,
        contentType: String,
        contentLength: Long,
        input: java.io.InputStream
    ) {
        if (!isSecure(gameDir)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
        gameDir.mkdirs()
        val destFile = File(gameDir, "${raStateName(romName, slot)}")

        if (contentType.startsWith("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim()
            if (boundary.isEmpty()) {
                sendJson(output, 400, """{"error":"missing boundary"}"""); return
            }
            val boundaryBytes = "--$boundary".toByteArray()
            val stream = MultipartStream(input, contentLength)
            stream.skipToBoundary(boundaryBytes)
            if (!stream.isEndBoundary(boundaryBytes)) {
                stream.readHeaderBlock()
                destFile.outputStream().use { fos ->
                    stream.streamBodyToBoundary(boundaryBytes, fos)
                }
            }
        } else {
            destFile.outputStream().use { fos ->
                val bos = java.io.BufferedOutputStream(fos, 262144)
                val buf = ByteArray(262144)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    remaining -= n
                }
                bos.flush()
            }
        }

        sendJson(output, 200, """{"ok":true,"slot":$slot,"file":"${escapeJson(destFile.name)}"}""")
    }

    private fun handleSlotDelete(output: OutputStream, gameDir: File, romName: String, slot: Int) {
        val stateFile = File(gameDir, "${raStateName(romName, slot)}")
        val thumbFile = File(gameDir, "${raStateName(romName, slot)}.png")
        if (!isSecure(stateFile)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
        if (!stateFile.exists()) { sendJson(output, 404, """{"error":"not found"}"""); return }
        stateFile.delete()
        thumbFile.delete()
        sendJson(output, 200, """{"ok":true}""")
    }

    private fun handleGames(
        method: String,
        segments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: java.io.InputStream,
        output: OutputStream,
    ) {
        val repo = romsRepository
        if (repo == null) {
            sendJson(output, 503, """{"error":"games not available"}"""); return
        }
        if (segments.isEmpty()) { sendJson(output, 404, """{"error":"not found"}"""); return }
        val platformTag = segments[0]

        if (segments.size == 1) {
            if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
            if (platformTag !in repo.knownPlatformTags()) {
                sendJson(output, 404, """{"error":"platform not found"}"""); return
            }
            sendJson(output, 200, GamesResponse.buildList(repo, cannoliRoot, platformTag, platformTag))
            return
        }

        val romId = segments[1].toLongOrNull()
        val rom = romId?.let { repo.gameById(it) }
        if (rom == null || !rom.platformTag.equals(platformTag, ignoreCase = true)) {
            sendJson(output, 404, """{"error":"game not found"}"""); return
        }
        val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)

        when (segments.size) {
            2 -> when (method) {
                "GET" -> sendJson(output, 200, GamesResponse.buildOne(repo, cannoliRoot, platformTag, platformTag, rom.id)!!)
                "DELETE" -> {
                    gameRomFiles(rom).forEach { if (isSecure(it)) it.delete() }
                    if (query["purge"] == "true") {
                        File(cannoliRoot, "Saves/$platformTag").listFiles { f ->
                            f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                        }?.forEach { it.delete() }
                        File(cannoliRoot, "Save States/$platformTag/$base").deleteRecursively()
                        File(cannoliRoot, "Guides/$platformTag/$base").deleteRecursively()
                        rom.artFile?.let { if (isSecure(it)) it.delete() }
                    }
                    repo.deleteRom(rom.id)
                    sendJson(output, 200, """{"ok":true}""")
                }
                else -> sendJson(output, 405, """{"error":"method not allowed"}""")
            }
            3 -> when (segments[2]) {
                "rom" -> {
                    val romFile = resolveRomFile(rom, query["file"])
                    if (romFile == null || !romFile.exists()) {
                        sendJson(output, 404, """{"error":"not found"}"""); return
                    }
                    when (method) {
                        "GET" -> sendFile(output, romFile, "application/octet-stream")
                        "DELETE" -> { romFile.delete(); sendJson(output, 200, """{"ok":true}""") }
                        else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                    }
                }
                "roms" -> {
                    if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                    sendJson(output, 200, gameRomsJson(rom))
                }
                "art" -> when (method) {
                    "GET" -> {
                        val art = rom.artFile
                        if (art == null || !art.exists() || !isSecure(art)) {
                            sendJson(output, 404, """{"error":"not found"}"""); return
                        }
                        sendFile(output, art, artMime(art))
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) { sendJson(output, 400, """{"error":"name param required"}"""); return }
                        val ext = name.substringAfterLast('.', "png").lowercase(java.util.Locale.ROOT)
                        val artDir = File(cannoliRoot, "Art/$platformTag")
                        artDir.listFiles { f ->
                            f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                        }?.forEach { it.delete() }
                        val dest = File(artDir, "$base.$ext")
                        if (!isSecure(dest)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                        streamUploadToFile(dest, headers["content-type"] ?: "", headers["content-length"]?.toLongOrNull() ?: 0L, input)
                        sendJson(output, 200, """{"ok":true}""")
                    }
                    "DELETE" -> {
                        val art = rom.artFile
                        if (art == null || !art.exists()) { sendJson(output, 404, """{"error":"not found"}"""); return }
                        if (!isSecure(art)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                        art.delete()
                        sendJson(output, 200, """{"ok":true}""")
                    }
                    else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                }
                "saves" -> when (method) {
                    "GET" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) {
                            sendJson(output, 200, gameSavesJson(platformTag, base))
                        } else {
                            val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                            if (!isSecure(target) || !target.exists()) {
                                sendJson(output, 404, """{"error":"not found"}"""); return
                            }
                            sendFile(output, target, "application/octet-stream")
                        }
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) { sendJson(output, 400, """{"error":"name param required"}"""); return }
                        val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                        if (ext.isEmpty()) { sendJson(output, 400, """{"error":"name needs an extension"}"""); return }
                        val dest = File(cannoliRoot, "Saves/$platformTag/$base.$ext")
                        if (!isSecure(dest)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                        streamUploadToFile(dest, headers["content-type"] ?: "", headers["content-length"]?.toLongOrNull() ?: 0L, input)
                        sendJson(output, 200, """{"ok":true}""")
                    }
                    "DELETE" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) { sendJson(output, 400, """{"error":"file param required"}"""); return }
                        val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                        if (!isSecure(target)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                        if (!target.exists()) { sendJson(output, 404, """{"error":"not found"}"""); return }
                        target.delete()
                        sendJson(output, 200, """{"ok":true}""")
                    }
                    else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                }
                "guides" -> {
                    val guideDir = File(cannoliRoot, "Guides/$platformTag/$base")
                    when (method) {
                        "GET" -> {
                            val fileName = query["file"]
                            if (fileName.isNullOrBlank()) {
                                sendJson(output, 200, gameGuidesJson(platformTag, base))
                            } else {
                                val target = File(guideDir, File(fileName).name)
                                if (!isSecure(target) || !target.exists()) {
                                    sendJson(output, 404, """{"error":"not found"}"""); return
                                }
                                sendFile(output, target, "application/octet-stream")
                            }
                        }
                        "POST" -> {
                            val name = query["name"]
                            if (name.isNullOrBlank()) { sendJson(output, 400, """{"error":"name param required"}"""); return }
                            val dest = File(guideDir, File(name).name)
                            if (!isSecure(dest)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                            streamUploadToFile(dest, headers["content-type"] ?: "", headers["content-length"]?.toLongOrNull() ?: 0L, input)
                            sendJson(output, 200, """{"ok":true}""")
                        }
                        "DELETE" -> {
                            val fileName = query["file"]
                            if (fileName.isNullOrBlank()) { sendJson(output, 400, """{"error":"file param required"}"""); return }
                            val target = File(guideDir, File(fileName).name)
                            if (!isSecure(target)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                            if (!target.exists()) { sendJson(output, 404, """{"error":"not found"}"""); return }
                            target.delete()
                            sendJson(output, 200, """{"ok":true}""")
                        }
                        else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                    }
                }
                "states" -> {
                    if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                    val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
                    handleSlotsList(output, gameDir, base)
                }
                else -> sendJson(output, 404, """{"error":"not found"}""")
            }
            4 -> {
                if (segments[2] != "states") { sendJson(output, 404, """{"error":"not found"}"""); return }
                val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
                if (segments[3] == "zip") {
                    if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                    if (!isSecure(gameDir)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                    val bytes = SlotsZip.build(gameDir, base)
                    if (bytes == null) { sendJson(output, 404, """{"error":"no states"}"""); return }
                    sendBytes(output, bytes, "application/zip")
                    return
                }
                val slot = segments[3].toIntOrNull()
                if (slot == null || slot < 0 || slot > 10) {
                    sendJson(output, 400, """{"error":"slot must be 0-10"}"""); return
                }
                if (!isSecure(gameDir)) { sendJson(output, 403, """{"error":"forbidden"}"""); return }
                when (method) {
                    "GET" -> {
                        val stateFile = File(gameDir, raStateName(base, slot))
                        if (!isSecure(stateFile) || !stateFile.exists()) {
                            sendJson(output, 404, """{"error":"not found"}"""); return
                        }
                        sendFile(output, stateFile, "application/octet-stream")
                    }
                    "POST" -> {
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        val contentType = headers["content-type"] ?: ""
                        handleSlotUpload(output, gameDir, base, slot, contentType, contentLength, input)
                    }
                    "DELETE" -> handleSlotDelete(output, gameDir, base, slot)
                    else -> sendJson(output, 405, """{"error":"method not allowed"}""")
                }
            }
            5 -> {
                if (segments[2] != "states" || segments[4] != "thumbnail") {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                if (method != "GET") { sendJson(output, 405, """{"error":"method not allowed"}"""); return }
                val slot = segments[3].toIntOrNull()
                if (slot == null || slot < 0 || slot > 10) {
                    sendJson(output, 400, """{"error":"slot must be 0-10"}"""); return
                }
                val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
                val thumbFile = File(gameDir, "${raStateName(base, slot)}.png")
                if (!isSecure(thumbFile) || !thumbFile.exists()) {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                sendFile(output, thumbFile, "image/png")
            }
            else -> sendJson(output, 404, """{"error":"not found"}""")
        }
    }

    private fun artMime(file: File): String = when (file.extension.lowercase(java.util.Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    private fun gameSavesJson(platformTag: String, base: String): String {
        val dir = File(cannoliRoot, "Saves/$platformTag")
        val files = dir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
        val items = files.joinToString(",") { f ->
            """{"name":"${escapeJson(f.name)}","size":${f.length()},"modified":${f.lastModified()}}"""
        }
        return """{"files":[$items]}"""
    }

    private fun gameGuidesJson(platformTag: String, base: String): String {
        val dir = File(cannoliRoot, "Guides/$platformTag/$base")
        val files = dir.listFiles { f -> f.isFile }
            ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
        val items = files.joinToString(",") { f ->
            """{"name":"${escapeJson(f.name)}","size":${f.length()},"modified":${f.lastModified()}}"""
        }
        return """{"files":[$items]}"""
    }

    private fun gameRomFiles(rom: dev.cannoli.scorza.model.Rom): List<File> = buildList {
        add(rom.path)
        rom.discFiles?.let { addAll(it) }
    }

    private fun gameRomsJson(rom: dev.cannoli.scorza.model.Rom): String {
        val romsRootPrefix = "${romsRootProvider().absolutePath}${File.separator}"
        val items = gameRomFiles(rom).joinToString(",") { f ->
            val rel = f.absolutePath.removePrefix(romsRootPrefix).replace(File.separatorChar, '/')
            val size = if (f.exists()) f.length() else 0L
            """{"name":"${escapeJson(f.name)}","path":"${escapeJson(rel)}","size":$size,"modified":${f.lastModified()}}"""
        }
        return """{"files":[$items]}"""
    }

    private fun resolveRomFile(rom: dev.cannoli.scorza.model.Rom, fileParam: String?): File? {
        if (fileParam.isNullOrBlank()) return rom.path
        val target = File(romsRootProvider(), fileParam)
        if (!isSecure(target)) return null
        return gameRomFiles(rom).firstOrNull { it.absolutePath == target.absolutePath }
    }

    private fun streamUploadToFile(
        destFile: File,
        contentType: String,
        contentLength: Long,
        input: java.io.InputStream,
    ) {
        destFile.parentFile?.mkdirs()
        if (contentType.startsWith("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim()
            if (boundary.isEmpty()) return
            val boundaryBytes = "--$boundary".toByteArray()
            val stream = MultipartStream(input, contentLength)
            stream.skipToBoundary(boundaryBytes)
            if (!stream.isEndBoundary(boundaryBytes)) {
                stream.readHeaderBlock()
                destFile.outputStream().use { fos -> stream.streamBodyToBoundary(boundaryBytes, fos) }
            }
        } else {
            destFile.outputStream().use { fos ->
                val bos = java.io.BufferedOutputStream(fos, 262144)
                val buf = ByteArray(262144)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    remaining -= n
                }
                bos.flush()
            }
        }
    }

    private fun handleArtwork(segments: List<String>, output: OutputStream) {
        val artDir = File(cannoliRoot, "Art")
        when (segments.size) {
            0 -> {
                val tags = artDir.listFiles { f -> f.isDirectory }
                    ?.filter { dir -> dir.listFiles { f -> f.isFile }?.isNotEmpty() == true }
                    ?.map { it.name }?.sorted() ?: emptyList()
                val json = tags.joinToString(",") { "\"${escapeJson(it)}\"" }
                sendJson(output, 200, """{"platforms":[$json]}""")
            }
            1 -> {
                val platformDir = File(artDir, segments[0])
                if (!isSecure(platformDir) || !platformDir.isDirectory) {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                val files = platformDir.listFiles { f -> f.isFile }
                    ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
                val items = files.joinToString(",") { f ->
                    """{"name":"${escapeJson(f.nameWithoutExtension)}","file":"${escapeJson(f.name)}","size":${f.length()}}"""
                }
                sendJson(output, 200, """{"platform":"${escapeJson(segments[0])}","art":[$items]}""")
            }
            2 -> {
                val platformDir = File(artDir, segments[0])
                if (!isSecure(platformDir) || !platformDir.isDirectory) {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                val gameName = segments[1]
                val artFile = platformDir.listFiles { f -> f.isFile && f.nameWithoutExtension == gameName }
                    ?.firstOrNull()
                if (artFile == null || !isSecure(artFile)) {
                    sendJson(output, 404, """{"error":"not found"}"""); return
                }
                val mime = when (artFile.extension.lowercase(java.util.Locale.ROOT)) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "bmp" -> "image/bmp"
                    else -> "application/octet-stream"
                }
                sendFile(output, artFile, mime)
            }
            else -> sendJson(output, 404, """{"error":"not found"}""")
        }
    }

    private fun sendFile(output: OutputStream, file: File, contentType: String) {
        val size = file.length()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $size\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    private fun sendBytes(output: OutputStream, bytes: ByteArray, contentType: String) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun handleUpload(
        output: OutputStream,
        destDir: File,
        contentType: String,
        contentLength: Long,
        input: java.io.InputStream
    ) {
        if (!isSecure(destDir)) {
            sendJson(output, 403, """{"error":"forbidden"}""")
            return
        }
        destDir.mkdirs()

        if (contentType.startsWith("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim()
            if (boundary.isEmpty()) {
                sendJson(output, 400, """{"error":"missing boundary"}""")
                return
            }
            handleMultipart(output, destDir, boundary, contentLength, input)
        } else {
            sendJson(output, 400, """{"error":"multipart upload required"}""")
        }
    }

    private fun handleMultipart(
        output: OutputStream,
        destDir: File,
        boundary: String,
        contentLength: Long,
        input: java.io.InputStream
    ) {
        val boundaryBytes = "--$boundary".toByteArray()
        val uploaded = mutableListOf<String>()
        val stream = MultipartStream(input, contentLength)

        stream.skipToBoundary(boundaryBytes)
        while (!stream.isEndBoundary(boundaryBytes)) {
            val headerBlock = stream.readHeaderBlock()
            val filenameMatch = FILENAME_REGEX.find(headerBlock)
            if (filenameMatch == null) {
                stream.skipToBoundary(boundaryBytes)
                continue
            }
            val rawName = filenameMatch.groupValues[1]
            val filename = sanitizeFilename(rawName)
            val dest = File(destDir, filename)
            var complete = false
            try {
                dest.outputStream().use { fos ->
                    complete = stream.streamBodyToBoundary(boundaryBytes, fos)
                }
            } catch (e: Exception) {
                complete = false
            }
            if (!complete) {
                try { dest.delete() } catch (_: Exception) {}
                return
            }
            uploaded.add(filename)
        }

        val files = uploaded.joinToString(",") { "\"${escapeJson(it)}\"" }
        sendJson(output, 200, """{"ok":true,"files":[$files]}""")
    }

    private class MultipartStream(
        private val input: java.io.InputStream,
        private val totalLength: Long
    ) {
        private val buf = ByteArray(262144 + 256)
        private var pos = 0
        private var len = 0
        private var totalRead = 0L
        private var streamEnded = false
        var lastBoundaryLine = ""
            private set

        private fun compact() {
            if (pos > 0) {
                val remaining = len - pos
                if (remaining > 0) System.arraycopy(buf, pos, buf, 0, remaining)
                len = remaining
                pos = 0
            }
        }

        private fun fillAtLeast(needed: Int): Boolean {
            while (len - pos < needed) {
                compact()
                if (totalRead >= totalLength) { streamEnded = true; return len - pos >= needed }
                val space = buf.size - len
                if (space <= 0) return len - pos >= needed
                val toRead = minOf(space.toLong(), totalLength - totalRead).toInt()
                val n = input.read(buf, len, toRead)
                if (n <= 0) { streamEnded = true; return len - pos >= needed }
                len += n
                totalRead += n
            }
            return true
        }

        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                if (!fillAtLeast(1)) return if (sb.isEmpty()) null else sb.toString()
                val start = pos
                while (pos < len) {
                    if (buf[pos] == '\n'.code.toByte()) {
                        sb.append(String(buf, start, pos - start, Charsets.UTF_8))
                        pos++
                        if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                        return sb.toString()
                    }
                    pos++
                }
                sb.append(String(buf, start, pos - start, Charsets.UTF_8))
            }
        }

        fun skipToBoundary(boundaryBytes: ByteArray) {
            val marker = String(boundaryBytes, Charsets.UTF_8)
            while (true) {
                val line = readLine() ?: break
                if (line.startsWith(marker)) {
                    lastBoundaryLine = line
                    return
                }
            }
            lastBoundaryLine = ""
        }

        fun isEndBoundary(boundaryBytes: ByteArray): Boolean {
            return lastBoundaryLine == String(boundaryBytes, Charsets.UTF_8) + "--" || lastBoundaryLine.isEmpty()
        }

        fun readHeaderBlock(): String {
            val sb = StringBuilder()
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                sb.appendLine(line)
            }
            return sb.toString()
        }

        fun streamBodyToBoundary(boundaryBytes: ByteArray, out: java.io.OutputStream): Boolean {
            val marker = byteArrayOf(0x0D, 0x0A) + boundaryBytes
            val mLen = marker.size
            val bos = java.io.BufferedOutputStream(out, 262144)

            while (true) {
                if (!fillAtLeast(mLen)) {
                    val avail = len - pos
                    if (avail > 0) bos.write(buf, pos, avail)
                    pos = len
                    bos.flush()
                    lastBoundaryLine = ""
                    return false
                }

                val avail = len - pos
                val searchEnd = pos + avail - mLen
                var found = -1
                var i = pos
                outer@ while (i <= searchEnd) {
                    if (buf[i] == marker[0]) {
                        for (j in 1 until mLen) {
                            if (buf[i + j] != marker[j]) { i++; continue@outer }
                        }
                        found = i
                        break
                    }
                    i++
                }

                if (found >= 0) {
                    if (found > pos) bos.write(buf, pos, found - pos)
                    bos.flush()
                    pos = found + mLen
                    val rest = readLine() ?: ""
                    lastBoundaryLine = String(boundaryBytes, Charsets.UTF_8) + rest
                    return true
                }

                val safeEnd = len - (mLen - 1)
                if (safeEnd > pos) {
                    bos.write(buf, pos, safeEnd - pos)
                    pos = safeEnd
                }
            }
        }
    }

    private fun serveStatic(output: OutputStream, endpoint: String) {
        val path = if (endpoint == "/") "index.html" else endpoint.removePrefix("/")
        if (path.contains("..")) {
            sendCors(output, 403, "text/plain", "forbidden".toByteArray())
            return
        }
        try {
            val body = assets.open("kitchen/$path").readBytes()
            sendCors(output, 200, mimeForPath(path), body)
        } catch (_: Exception) {
            // SPA fallback: serve index.html for routes that don't match a static asset
            try {
                val body = assets.open("kitchen/index.html").readBytes()
                sendCors(output, 200, "text/html", body)
            } catch (_: Exception) {
                sendCors(output, 404, "text/plain", "not found".toByteArray())
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

    private fun handleAuthStatus(output: OutputStream) {
        sendJson(output, 200, """{"required":${!codeBypass}}""")
    }

    private fun sendUnauthorized(output: OutputStream) {
        val body = """{"error":"unauthorized"}""".toByteArray()
        val header = buildString {
            append("HTTP/1.1 401 Unauthorized\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")

            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun isSecure(file: File): Boolean {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        val canonical = file.canonicalPath
        if (canonical.startsWith(cannoliRoot.canonicalPath)) return true
        val romsCanonical = try { romsRootProvider().canonicalPath } catch (_: Exception) { return false }
        return canonical.startsWith(romsCanonical)
    }

    private fun sanitizeFilename(name: String): String {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
            .replace(Regex("[/\\\\]"), "_").trim()
    }

    private fun mimeForPath(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun sendJson(output: OutputStream, status: Int, json: String) {
        sendCors(output, status, "application/json", json.toByteArray())
    }

    private fun sendCors(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun raStateName(romName: String, slot: Int): String = when (slot) {
        0 -> "$romName.state.auto"
        1 -> "$romName.state"
        else -> "$romName.state${slot - 1}"
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun generatePin(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private val FILENAME_REGEX = Regex("""filename="([^"]+)"""")
        private val RESOURCE_DIRS = mapOf(
            "roms" to "Roms",
            "art" to "Art",
            "saves" to "Saves",
            "states" to "Save States",
            "bios" to "BIOS",
            "wallpapers" to "Wallpapers",
            "guides" to "Guides"
        )
    }
}
