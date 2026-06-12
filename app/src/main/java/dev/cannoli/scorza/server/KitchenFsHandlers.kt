package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

data class KitchenVolume(val id: String, val label: String, val root: File)

internal fun KitchenHttpServer.handleFs(
    method: String,
    segments: List<String>,
    query: Map<String, String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    if (segments.isEmpty()) {
        if (method != "GET") return errorResponse(405, "method not allowed")
        return handleVolumes()
    }
    val volume = volumesProvider().firstOrNull { it.id == segments[0] }
        ?: return errorResponse(404, "unknown volume")
    val roots = listOf(volume.root)
    val subpath = segments.drop(1).joinToString("/")
    val target = if (subpath.isEmpty()) volume.root else File(volume.root, subpath)
    val displayPath = if (subpath.isEmpty()) volume.id else "${volume.id}/$subpath"
    return when (method) {
        "GET" -> {
            if (target.isFile && isSecure(target, roots)) {
                fileResponse(target, mimeForPath(target.name))
            } else {
                handleList(target, displayPath, query["recursive"] == "true", roots)
            }
        }
        "POST" -> handleUpload(target, session, roots)
        "PUT" -> {
            if (subpath.isEmpty()) errorResponse(400, "path required")
            else handleMkdir(target, roots)
        }
        "DELETE" -> {
            if (subpath.isEmpty()) errorResponse(400, "path required")
            else handleDelete(target.parentFile ?: target, target.name, roots)
        }
        "PATCH" -> {
            if (subpath.isEmpty()) errorResponse(400, "path required")
            else handleMove(volume.root, subpath, readBody(session), roots, requireSameTopDir = false)
        }
        else -> errorResponse(405, "method not allowed")
    }
}

private fun KitchenHttpServer.handleVolumes(): Response {
    val entries = volumesProvider().map { v ->
        VolumeEntry(
            id = v.id,
            label = v.label,
            totalBytes = v.root.totalSpace,
            freeBytes = v.root.usableSpace,
        )
    }
    return jsonResponse(200, VolumesResponse.serializer(), VolumesResponse(entries))
}
