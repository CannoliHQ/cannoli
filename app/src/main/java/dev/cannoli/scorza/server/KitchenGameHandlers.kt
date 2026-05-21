package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

internal fun KitchenHttpServer.artMime(file: File): String = when (file.extension.lowercase(java.util.Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "application/octet-stream"
}

internal fun KitchenHttpServer.gameSavesJson(platformTag: String, base: String): String {
    val dir = File(cannoliRoot, "Saves/$platformTag")
    val files = dir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true) }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.joinToString(",") { f ->
        """{"name":"${escapeJson(f.name)}","size":${f.length()},"modified":${f.lastModified()}}"""
    }
    return """{"files":[$items]}"""
}

internal fun KitchenHttpServer.gameGuidesJson(platformTag: String, base: String): String {
    val dir = File(cannoliRoot, "Guides/$platformTag/$base")
    val files = dir.listFiles { f -> f.isFile }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.joinToString(",") { f ->
        """{"name":"${escapeJson(f.name)}","size":${f.length()},"modified":${f.lastModified()}}"""
    }
    return """{"files":[$items]}"""
}

internal fun KitchenHttpServer.gameRomFiles(rom: dev.cannoli.scorza.model.Rom): List<File> = buildList {
    add(rom.path)
    rom.discFiles?.let { addAll(it) }
}

internal fun KitchenHttpServer.gameRomsJson(rom: dev.cannoli.scorza.model.Rom): String {
    val romsRootPrefix = "${romsRootProvider().absolutePath}${File.separator}"
    val items = gameRomFiles(rom).joinToString(",") { f ->
        val rel = f.absolutePath.removePrefix(romsRootPrefix).replace(File.separatorChar, '/')
        val size = if (f.exists()) f.length() else 0L
        """{"name":"${escapeJson(f.name)}","path":"${escapeJson(rel)}","size":$size,"modified":${f.lastModified()}}"""
    }
    return """{"files":[$items]}"""
}

internal fun KitchenHttpServer.resolveRomFile(rom: dev.cannoli.scorza.model.Rom, fileParam: String?): File? {
    if (fileParam.isNullOrBlank()) return rom.path
    val target = File(romsRootProvider(), fileParam)
    if (!isSecure(target)) return null
    return gameRomFiles(rom).firstOrNull { it.absolutePath == target.absolutePath }
}

internal fun KitchenHttpServer.streamUploadToFile(destFile: File, session: NanoHTTPD.IHTTPSession): Response? {
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return jsonResponse(400, """{"error":"chunked upload not supported"}""")
    }
    destFile.parentFile?.mkdirs()
    val contentType = session.headers["content-type"] ?: ""
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    if (contentType.startsWith("multipart/form-data")) {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { destFile }
    } else {
        destFile.outputStream().use { fos ->
            val bos = java.io.BufferedOutputStream(fos, 262144)
            val buf = ByteArray(262144)
            var remaining = contentLength
            val input = session.inputStream
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                bos.write(buf, 0, n)
                remaining -= n
            }
            bos.flush()
        }
    }
    return null
}

internal fun KitchenHttpServer.handleGames(
    method: String,
    segments: List<String>,
    query: Map<String, String>,
    headers: Map<String, String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    val repo = romsRepository
        ?: return jsonResponse(503, """{"error":"games not available"}""")
    if (segments.isEmpty()) return jsonResponse(404, """{"error":"not found"}""")
    val platformTag = segments[0]

    if (segments.size == 1) {
        if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
        if (platformTag !in repo.knownPlatformTags()) {
            return jsonResponse(404, """{"error":"platform not found"}""")
        }
        return jsonResponse(200, GamesResponse.buildList(repo, cannoliRoot, platformTag, platformTag))
    }

    val romId = segments[1].toLongOrNull()
    val rom = romId?.let { repo.gameById(it) }
    if (rom == null || !rom.platformTag.equals(platformTag, ignoreCase = true)) {
        return jsonResponse(404, """{"error":"game not found"}""")
    }
    val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)

    when (segments.size) {
        2 -> return when (method) {
            "GET" -> jsonResponse(200, GamesResponse.buildOne(repo, cannoliRoot, platformTag, platformTag, rom.id)!!)
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
                jsonResponse(200, """{"ok":true}""")
            }
            else -> jsonResponse(405, """{"error":"method not allowed"}""")
        }
        3 -> return when (segments[2]) {
            "rom" -> {
                val romFile = resolveRomFile(rom, query["file"])
                if (romFile == null || !romFile.exists()) {
                    return jsonResponse(404, """{"error":"not found"}""")
                }
                when (method) {
                    "GET" -> fileResponse(romFile, "application/octet-stream")
                    "DELETE" -> { romFile.delete(); jsonResponse(200, """{"ok":true}""") }
                    else -> jsonResponse(405, """{"error":"method not allowed"}""")
                }
            }
            "roms" -> {
                if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
                jsonResponse(200, gameRomsJson(rom))
            }
            "art" -> when (method) {
                "GET" -> {
                    val art = rom.artFile
                    if (art == null || !art.exists() || !isSecure(art)) {
                        return jsonResponse(404, """{"error":"not found"}""")
                    }
                    fileResponse(art, artMime(art))
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return jsonResponse(400, """{"error":"name param required"}""")
                    val ext = name.substringAfterLast('.', "png").lowercase(java.util.Locale.ROOT)
                    val artDir = File(cannoliRoot, "Art/$platformTag")
                    artDir.listFiles { f ->
                        f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                    }?.forEach { it.delete() }
                    val dest = File(artDir, "$base.$ext")
                    if (!isSecure(dest)) return jsonResponse(403, """{"error":"forbidden"}""")
                    streamUploadToFile(dest, session)?.let { return it }
                    jsonResponse(200, """{"ok":true}""")
                }
                "DELETE" -> {
                    val art = rom.artFile
                    if (art == null || !art.exists()) return jsonResponse(404, """{"error":"not found"}""")
                    if (!isSecure(art)) return jsonResponse(403, """{"error":"forbidden"}""")
                    art.delete()
                    jsonResponse(200, """{"ok":true}""")
                }
                else -> jsonResponse(405, """{"error":"method not allowed"}""")
            }
            "saves" -> when (method) {
                "GET" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) {
                        jsonResponse(200, gameSavesJson(platformTag, base))
                    } else {
                        val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                        if (!isSecure(target) || !target.exists()) {
                            return jsonResponse(404, """{"error":"not found"}""")
                        }
                        fileResponse(target, "application/octet-stream")
                    }
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return jsonResponse(400, """{"error":"name param required"}""")
                    val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    if (ext.isEmpty()) return jsonResponse(400, """{"error":"name needs an extension"}""")
                    val dest = File(cannoliRoot, "Saves/$platformTag/$base.$ext")
                    if (!isSecure(dest)) return jsonResponse(403, """{"error":"forbidden"}""")
                    streamUploadToFile(dest, session)?.let { return it }
                    jsonResponse(200, """{"ok":true}""")
                }
                "DELETE" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) return jsonResponse(400, """{"error":"file param required"}""")
                    val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                    if (!isSecure(target)) return jsonResponse(403, """{"error":"forbidden"}""")
                    if (!target.exists()) return jsonResponse(404, """{"error":"not found"}""")
                    target.delete()
                    jsonResponse(200, """{"ok":true}""")
                }
                else -> jsonResponse(405, """{"error":"method not allowed"}""")
            }
            "guides" -> {
                val guideDir = File(cannoliRoot, "Guides/$platformTag/$base")
                when (method) {
                    "GET" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) {
                            jsonResponse(200, gameGuidesJson(platformTag, base))
                        } else {
                            val target = File(guideDir, File(fileName).name)
                            if (!isSecure(target) || !target.exists()) {
                                return jsonResponse(404, """{"error":"not found"}""")
                            }
                            fileResponse(target, "application/octet-stream")
                        }
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) return jsonResponse(400, """{"error":"name param required"}""")
                        val dest = File(guideDir, File(name).name)
                        if (!isSecure(dest)) return jsonResponse(403, """{"error":"forbidden"}""")
                        streamUploadToFile(dest, session)?.let { return it }
                        jsonResponse(200, """{"ok":true}""")
                    }
                    "DELETE" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) return jsonResponse(400, """{"error":"file param required"}""")
                        val target = File(guideDir, File(fileName).name)
                        if (!isSecure(target)) return jsonResponse(403, """{"error":"forbidden"}""")
                        if (!target.exists()) return jsonResponse(404, """{"error":"not found"}""")
                        target.delete()
                        jsonResponse(200, """{"ok":true}""")
                    }
                    else -> jsonResponse(405, """{"error":"method not allowed"}""")
                }
            }
            "states" -> {
                if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
                val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
                handleSlotsList(gameDir, base)
            }
            else -> jsonResponse(404, """{"error":"not found"}""")
        }
        4 -> {
            if (segments[2] != "states") return jsonResponse(404, """{"error":"not found"}""")
            val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
            if (segments[3] == "zip") {
                if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
                if (!isSecure(gameDir)) return jsonResponse(403, """{"error":"forbidden"}""")
                val bytes = SlotsZip.build(gameDir, base)
                    ?: return jsonResponse(404, """{"error":"no states"}""")
                return bytesResponse(200, "application/zip", bytes)
            }
            val slot = segments[3].toIntOrNull()
            if (slot == null || slot < 0 || slot > 10) {
                return jsonResponse(400, """{"error":"slot must be 0-10"}""")
            }
            if (!isSecure(gameDir)) return jsonResponse(403, """{"error":"forbidden"}""")
            return when (method) {
                "GET" -> {
                    val stateFile = File(gameDir, raStateName(base, slot))
                    if (!isSecure(stateFile) || !stateFile.exists()) {
                        return jsonResponse(404, """{"error":"not found"}""")
                    }
                    fileResponse(stateFile, "application/octet-stream")
                }
                "POST" -> handleSlotUpload(gameDir, base, slot, session)
                "DELETE" -> handleSlotDelete(gameDir, base, slot)
                else -> jsonResponse(405, """{"error":"method not allowed"}""")
            }
        }
        5 -> {
            if (segments[2] != "states" || segments[4] != "thumbnail") {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            if (method != "GET") return jsonResponse(405, """{"error":"method not allowed"}""")
            val slot = segments[3].toIntOrNull()
            if (slot == null || slot < 0 || slot > 10) {
                return jsonResponse(400, """{"error":"slot must be 0-10"}""")
            }
            val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
            val thumbFile = File(gameDir, "${raStateName(base, slot)}.png")
            if (!isSecure(thumbFile) || !thumbFile.exists()) {
                return jsonResponse(404, """{"error":"not found"}""")
            }
            return fileResponse(thumbFile, "image/png")
        }
        else -> return jsonResponse(404, """{"error":"not found"}""")
    }
}
