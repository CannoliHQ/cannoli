package dev.cannoli.scorza.romm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientTokenExchangePayload(val code: String)

@Serializable
data class ClientTokenDto(
    val name: String = "",
    @SerialName("raw_token") val rawToken: String = "",
)

@Serializable
data class PlatformDto(
    val id: Int,
    val slug: String,
    @SerialName("fs_slug") val fsSlug: String = "",
    @SerialName("rom_count") val romCount: Int = 0,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RomFileDto(
    val id: Int,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    @SerialName("crc_hash") val crcHash: String? = null,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
)

@Serializable
data class RomMetadatumDto(
    val genres: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    @SerialName("game_modes") val gameModes: List<String> = emptyList(),
    @SerialName("first_release_date") val firstReleaseDate: Long? = null,
)

@Serializable
data class SimpleRomDto(
    val id: Int,
    @SerialName("platform_id") val platformId: Int,
    @SerialName("platform_slug") val platformSlug: String = "",
    @SerialName("fs_name") val fsName: String,
    @SerialName("fs_name_no_ext") val fsNameNoExt: String = "",
    @SerialName("fs_extension") val fsExtension: String = "",
    @SerialName("fs_size_bytes") val fsSizeBytes: Long = 0,
    val name: String? = null,
    val summary: String? = null,
    val revision: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("crc_hash") val crcHash: String? = null,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
    @SerialName("path_cover_large") val pathCoverLarge: String? = null,
    @SerialName("url_cover") val urlCover: String? = null,
    @SerialName("has_multiple_files") val hasMultipleFiles: Boolean = false,
    val files: List<RomFileDto> = emptyList(),
    val metadatum: RomMetadatumDto? = null,
    @SerialName("ss_metadata") val ssMetadata: SsMetadataDto? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SsMetadataDto(
    @SerialName("box2d_url") val box2dUrl: String? = null,
    @SerialName("box3d_url") val box3dUrl: String? = null,
    @SerialName("miximage_url") val miximageUrl: String? = null,
    @SerialName("title_screen_url") val titleScreenUrl: String? = null,
    @SerialName("screenshot_url") val screenshotUrl: String? = null,
    @SerialName("marquee_url") val marqueeUrl: String? = null,
    @SerialName("manual_url") val manualUrl: String? = null,
)

@Serializable
data class RomsPageDto(
    val items: List<SimpleRomDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class FirmwareDto(
    val id: Int,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
    @SerialName("crc_hash") val crcHash: String? = null,
)

@Serializable
data class SystemDict(
    @SerialName("VERSION") val version: String = "",
)

@Serializable
data class HeartbeatResponse(
    @SerialName("SYSTEM") val system: SystemDict = SystemDict(),
)

@Serializable
data class UserMeDto(
    val username: String = "",
)
