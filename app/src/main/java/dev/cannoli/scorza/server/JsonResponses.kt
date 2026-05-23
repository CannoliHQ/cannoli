package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val serverJson: Json = Json { explicitNulls = false }

@Serializable
internal data class OkResponse(
    val ok: Boolean = true,
    val existed: Boolean? = null,
)

@Serializable
internal data class ErrorResponse(val error: String)

@Serializable
internal data class TagsResponse(val tags: List<String>)

@Serializable
internal data class PlatformsResponse(val platforms: List<String>)

@Serializable
internal data class FileEntry(
    val name: String,
    val size: Long,
    val modified: Long,
)

@Serializable
internal data class FilesResponse(val files: List<FileEntry>)

@Serializable
internal data class RomFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
)

@Serializable
internal data class RomFilesResponse(val files: List<RomFileEntry>)

@Serializable
internal data class DirEntry(
    val name: String,
    val type: String,
    val size: Long,
)

@Serializable
internal data class DirListResponse(
    val path: String,
    val entries: List<DirEntry>,
)

@Serializable
internal data class UploadResponse(
    val ok: Boolean = true,
    val files: List<String>,
)

@Serializable
internal data class ArtEntry(
    val name: String,
    val file: String,
    val size: Long,
)

@Serializable
internal data class ArtListResponse(
    val platform: String,
    val art: List<ArtEntry>,
)

@Serializable
internal data class SlotEntry(
    val slot: Int,
    val label: String,
    val exists: Boolean,
    val size: Long,
    val modified: Long,
    val thumbnail: Boolean,
)

@Serializable
internal data class SlotsListResponse(
    val game: String,
    val slots: List<SlotEntry>,
)

@Serializable
internal data class SlotGamesResponse(
    val platform: String,
    val games: List<String>,
)

@Serializable
internal data class SlotUploadResponse(
    val ok: Boolean = true,
    val slot: Int,
    val file: String,
)

@Serializable
internal data class AuthStatusResponse(val required: Boolean)

@Serializable
internal data class InfoResponse(val name: String, val version: Int)

internal fun <T> KitchenHttpServer.jsonResponse(code: Int, serializer: KSerializer<T>, value: T): Response =
    jsonResponse(code, serverJson.encodeToString(serializer, value))

internal fun KitchenHttpServer.okResponse(code: Int = 200): Response =
    jsonResponse(code, OkResponse.serializer(), OkResponse())

internal fun KitchenHttpServer.errorResponse(code: Int, message: String): Response =
    jsonResponse(code, ErrorResponse.serializer(), ErrorResponse(message))
