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

internal fun KitchenHttpServer.gameSavesResponse(platformTag: String, base: String): Response {
    val dir = File(cannoliRoot, "Saves/$platformTag")
    val files = dir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true) }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.map { f -> FileEntry(f.name, f.length(), f.lastModified()) }
    return jsonResponse(200, FilesResponse.serializer(), FilesResponse(items))
}

internal fun KitchenHttpServer.gameGuidesResponse(platformTag: String, base: String): Response {
    val dir = File(cannoliRoot, "Guides/$platformTag/$base")
    val files = dir.listFiles { f -> f.isFile }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.map { f -> FileEntry(f.name, f.length(), f.lastModified()) }
    return jsonResponse(200, FilesResponse.serializer(), FilesResponse(items))
}

internal fun KitchenHttpServer.gameCheatsResponse(platformTag: String, base: String): Response {
    val dir = File(cannoliRoot, "Cheats/$platformTag/$base")
    val files = dir.listFiles { f -> f.isFile && f.extension.equals("cht", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.map { f -> FileEntry(f.name, f.length(), f.lastModified()) }
    return jsonResponse(200, FilesResponse.serializer(), FilesResponse(items))
}

internal fun KitchenHttpServer.gameRomFiles(rom: dev.cannoli.scorza.model.Rom): List<File> =
    romDirectoryWalker?.gameFiles(rom.path) ?: listOf(rom.path)

internal fun KitchenHttpServer.gameRomsResponse(rom: dev.cannoli.scorza.model.Rom): Response {
    val romsRootPrefix = "${romsRootProvider().absolutePath}${File.separator}"
    val items = gameRomFiles(rom).map { f ->
        val rel = f.absolutePath.removePrefix(romsRootPrefix).replace(File.separatorChar, '/')
        val size = if (f.exists()) f.length() else 0L
        RomFileEntry(name = f.name, path = rel, size = size, modified = f.lastModified())
    }
    return jsonResponse(200, RomFilesResponse.serializer(), RomFilesResponse(items))
}

internal fun KitchenHttpServer.resolveRomFile(rom: dev.cannoli.scorza.model.Rom, fileParam: String?): File? {
    if (fileParam.isNullOrBlank()) return rom.path
    val target = File(romsRootProvider(), fileParam)
    if (!isSecure(target)) return null
    return gameRomFiles(rom).firstOrNull { it.absolutePath == target.absolutePath }
}

internal fun KitchenHttpServer.streamUploadToFile(destFile: File, session: NanoHTTPD.IHTTPSession): Response? {
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return errorResponse(400, "chunked upload not supported")
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
        ?: return errorResponse(503, "games not available")
    if (segments.isEmpty()) return errorResponse(404, "not found")
    val platformTag = segments[0]

    if (segments.size == 1) {
        if (method != "GET") return errorResponse(405, "method not allowed")
        if (platformTag !in repo.knownPlatformTags()) {
            return errorResponse(404, "platform not found")
        }
        return jsonResponse(200, GamesResponse.buildList(repo, cannoliRoot, romsRootProvider(), platformTag, platformTag, romDirectoryWalker))
    }

    val romId = segments[1].toLongOrNull()
    val rom = romId?.let { repo.gameById(it) }
    if (rom == null || !rom.platformTag.equals(platformTag, ignoreCase = true)) {
        return errorResponse(404, "game not found")
    }
    val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)

    when (segments.size) {
        2 -> return when (method) {
            "GET" -> jsonResponse(200, GamesResponse.buildOne(repo, cannoliRoot, romsRootProvider(), platformTag, platformTag, rom.id, romDirectoryWalker)!!)
            "DELETE" -> {
                val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                if (gameDir != null) gameDir.deleteRecursively()
                else gameRomFiles(rom).forEach { if (isSecure(it)) it.delete() }
                if (query["purge"] == "true") {
                    File(cannoliRoot, "Saves/$platformTag").listFiles { f ->
                        f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                    }?.forEach { it.delete() }
                    File(cannoliRoot, "Save States/$platformTag/$base").deleteRecursively()
                    File(cannoliRoot, "Guides/$platformTag/$base").deleteRecursively()
                    File(cannoliRoot, "Cheats/$platformTag/$base").deleteRecursively()
                    rom.artFile?.let { if (isSecure(it)) it.delete() }
                }
                repo.deleteRom(rom.id)
                scanPlatform?.invoke(platformTag)
                okResponse()
            }
            else -> errorResponse(405, "method not allowed")
        }
        3 -> return when (segments[2]) {
            "rom" -> {
                val romFile = resolveRomFile(rom, query["file"])
                if (romFile == null || !romFile.exists()) {
                    return errorResponse(404, "not found")
                }
                when (method) {
                    "GET" -> fileResponse(romFile, "application/octet-stream")
                    "DELETE" -> {
                        val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                        if (gameDir != null) gameDir.deleteRecursively() else romFile.delete()
                        scanPlatform?.invoke(platformTag)
                        okResponse()
                    }
                    else -> errorResponse(405, "method not allowed")
                }
            }
            "roms" -> {
                if (method != "GET") return errorResponse(405, "method not allowed")
                gameRomsResponse(rom)
            }
            "art" -> when (method) {
                "GET" -> {
                    val art = GamesResponse.resolveArtFile(cannoliRoot, platformTag, base)
                    if (art == null || !art.exists() || !isSecure(art)) {
                        return errorResponse(404, "not found")
                    }
                    fileResponse(art, artMime(art))
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                    val ext = name.substringAfterLast('.', "png").lowercase(java.util.Locale.ROOT)
                    val artDir = File(cannoliRoot, "Art/$platformTag")
                    artDir.listFiles { f ->
                        f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                    }?.forEach { it.delete() }
                    val dest = File(artDir, "$base.$ext")
                    if (!isSecure(dest)) return errorResponse(403, "forbidden")
                    streamUploadToFile(dest, session)?.let { return it }
                    okResponse()
                }
                "DELETE" -> {
                    val art = GamesResponse.resolveArtFile(cannoliRoot, platformTag, base)
                    if (art == null || !art.exists()) return errorResponse(404, "not found")
                    if (!isSecure(art)) return errorResponse(403, "forbidden")
                    art.delete()
                    okResponse()
                }
                else -> errorResponse(405, "method not allowed")
            }
            "saves" -> when (method) {
                "GET" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) {
                        gameSavesResponse(platformTag, base)
                    } else {
                        val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                        if (!isSecure(target) || !target.exists()) {
                            return errorResponse(404, "not found")
                        }
                        fileResponse(target, "application/octet-stream")
                    }
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                    val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    if (ext.isEmpty()) return errorResponse(400, "name needs an extension")
                    val dest = File(cannoliRoot, "Saves/$platformTag/$base.$ext")
                    if (!isSecure(dest)) return errorResponse(403, "forbidden")
                    streamUploadToFile(dest, session)?.let { return it }
                    okResponse()
                }
                "DELETE" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) return errorResponse(400, "file param required")
                    val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                    if (!isSecure(target)) return errorResponse(403, "forbidden")
                    if (!target.exists()) return errorResponse(404, "not found")
                    target.delete()
                    okResponse()
                }
                else -> errorResponse(405, "method not allowed")
            }
            "guides" -> {
                val guideDir = File(cannoliRoot, "Guides/$platformTag/$base")
                when (method) {
                    "GET" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) {
                            gameGuidesResponse(platformTag, base)
                        } else {
                            val target = File(guideDir, File(fileName).name)
                            if (!isSecure(target) || !target.exists()) {
                                return errorResponse(404, "not found")
                            }
                            fileResponse(target, "application/octet-stream")
                        }
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                        val dest = File(guideDir, File(name).name)
                        if (!isSecure(dest)) return errorResponse(403, "forbidden")
                        streamUploadToFile(dest, session)?.let { return it }
                        okResponse()
                    }
                    "DELETE" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) return errorResponse(400, "file param required")
                        val target = File(guideDir, File(fileName).name)
                        if (!isSecure(target)) return errorResponse(403, "forbidden")
                        if (!target.exists()) return errorResponse(404, "not found")
                        target.delete()
                        okResponse()
                    }
                    else -> errorResponse(405, "method not allowed")
                }
            }
            "cheats" -> {
                val cheatDir = File(cannoliRoot, "Cheats/$platformTag/$base")
                when (method) {
                    "GET" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) {
                            gameCheatsResponse(platformTag, base)
                        } else {
                            val target = File(cheatDir, File(fileName).name)
                            if (!isSecure(target) || !target.exists()) {
                                return errorResponse(404, "not found")
                            }
                            fileResponse(target, "application/octet-stream")
                        }
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                        if (!name.lowercase(java.util.Locale.ROOT).endsWith(".cht")) {
                            return errorResponse(400, "cheat files must have a .cht extension")
                        }
                        val dest = File(cheatDir, File(name).name)
                        if (!isSecure(dest)) return errorResponse(403, "forbidden")
                        streamUploadToFile(dest, session)?.let { return it }
                        okResponse()
                    }
                    "DELETE" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) return errorResponse(400, "file param required")
                        val target = File(cheatDir, File(fileName).name)
                        if (!isSecure(target)) return errorResponse(403, "forbidden")
                        if (!target.exists()) return errorResponse(404, "not found")
                        target.delete()
                        okResponse()
                    }
                    else -> errorResponse(405, "method not allowed")
                }
            }
            "states" -> {
                if (method != "GET") return errorResponse(405, "method not allowed")
                val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
                handleSlotsList(gameDir, base)
            }
            "move" -> {
                if (method != "POST") return errorResponse(405, "method not allowed")
                val folder = query["folder"] ?: ""
                val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                val unit = gameDir ?: rom.path
                val romsRoot = romsRootProvider()
                var platformDir = unit
                while (platformDir.parentFile != null && platformDir.parentFile != romsRoot) {
                    platformDir = platformDir.parentFile!!
                }
                val destDir = if (folder.isBlank()) platformDir else File(platformDir, folder)
                if (!destDir.isDirectory) return errorResponse(400, "destination folder not found")
                val target = File(destDir, unit.name)
                if (!isSecure(unit) || !isSecure(target)) return errorResponse(403, "forbidden")
                if (target.canonicalPath == unit.canonicalPath) return okResponse()
                if (target.exists()) return errorResponse(409, "a game with that name already exists in the destination")
                if (!unit.renameTo(target)) return errorResponse(500, "move failed")
                val newPrimary = if (gameDir == null) target else File(target, rom.path.name)
                val newRelPath = newPrimary.absolutePath.removePrefix("${romsRoot.absolutePath}${File.separator}")
                repo.updateRomPath(rom.id, newRelPath)
                scanPlatform?.invoke(platformTag)
                okResponse()
            }
            "rename" -> {
                if (method != "POST") return errorResponse(405, "method not allowed")
                val name = query["name"]
                if (name.isNullOrBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                    return errorResponse(400, "invalid name")
                }
                if (!isSecure(rom.path)) return errorResponse(403, "forbidden")
                val gameDirBefore = romDirectoryWalker?.gameDirectory(rom.path)
                when (romDirectoryWalker?.renameGame(rom.path, name)) {
                    dev.cannoli.scorza.util.RomDirectoryWalker.RenameOutcome.RENAMED -> {
                        val ext = rom.path.extension
                        val newPrimary = if (gameDirBefore == null) {
                            File(rom.path.parentFile, if (ext.isEmpty()) name else "$name.$ext")
                        } else {
                            val newDir = File(gameDirBefore.parentFile, name)
                            File(newDir, if (ext.isEmpty()) name else "$name.$ext")
                        }
                        val romsRoot = romsRootProvider()
                        val newRelPath = newPrimary.absolutePath.removePrefix("${romsRoot.absolutePath}${File.separator}")
                        repo.updateRomPath(rom.id, newRelPath)
                        scanPlatform?.invoke(platformTag)
                        okResponse()
                    }
                    dev.cannoli.scorza.util.RomDirectoryWalker.RenameOutcome.NAME_TAKEN ->
                        errorResponse(409, "a game with that name already exists")
                    else -> errorResponse(500, "rename failed")
                }
            }
            else -> errorResponse(404, "not found")
        }
        4 -> {
            if (segments[2] != "states") return errorResponse(404, "not found")
            val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
            if (segments[3] == "zip") {
                if (method != "GET") return errorResponse(405, "method not allowed")
                if (!isSecure(gameDir)) return errorResponse(403, "forbidden")
                val bytes = SlotsZip.build(gameDir, base)
                    ?: return errorResponse(404, "no states")
                return corsResponse(200, "application/zip", bytes)
            }
            val slot = segments[3].toIntOrNull()
            if (slot == null || slot < 0 || slot > 10) {
                return errorResponse(400, "slot must be 0-10")
            }
            if (!isSecure(gameDir)) return errorResponse(403, "forbidden")
            return when (method) {
                "GET" -> {
                    val stateFile = File(gameDir, raStateName(base, slot))
                    if (!isSecure(stateFile) || !stateFile.exists()) {
                        return errorResponse(404, "not found")
                    }
                    fileResponse(stateFile, "application/octet-stream")
                }
                "POST" -> handleSlotUpload(gameDir, base, slot, session)
                "DELETE" -> handleSlotDelete(gameDir, base, slot)
                else -> errorResponse(405, "method not allowed")
            }
        }
        5 -> {
            if (segments[2] != "states" || segments[4] != "thumbnail") {
                return errorResponse(404, "not found")
            }
            if (method != "GET") return errorResponse(405, "method not allowed")
            val slot = segments[3].toIntOrNull()
            if (slot == null || slot < 0 || slot > 10) {
                return errorResponse(400, "slot must be 0-10")
            }
            val gameDir = File(cannoliRoot, "Save States/$platformTag/$base")
            val thumbFile = File(gameDir, "${raStateName(base, slot)}.png")
            if (!isSecure(thumbFile) || !thumbFile.exists()) {
                return errorResponse(404, "not found")
            }
            return fileResponse(thumbFile, "image/png")
        }
        else -> return errorResponse(404, "not found")
    }
}
