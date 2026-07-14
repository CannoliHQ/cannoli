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
    val firmware: List<FirmwareDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RomSiblingDto(
    val id: Int,
    @SerialName("is_main_sibling") val isMainSibling: Boolean = false,
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
    @SerialName("merged_screenshots") val mergedScreenshots: List<String> = emptyList(),
    @SerialName("has_manual") val hasManual: Boolean = false,
    @SerialName("path_manual") val pathManual: String? = null,
    @SerialName("has_multiple_files") val hasMultipleFiles: Boolean = false,
    val files: List<RomFileDto> = emptyList(),
    @SerialName("sibling_roms") val siblings: List<RomSiblingDto> = emptyList(),
    @SerialName("is_main_sibling") val isMainSibling: Boolean = false,
    val metadatum: RomMetadatumDto? = null,
    @SerialName("ss_metadata") val ssMetadata: SsMetadataDto? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

// Only the resources RomM stores and serves itself. The provider urls in ss_metadata (box3d_url and
// friends) point straight at ScreenScraper and are intentionally left unparsed.
@Serializable
data class SsMetadataDto(
    @SerialName("box3d_path") val box3dPath: String? = null,
    @SerialName("miximage_path") val miximagePath: String? = null,
    @SerialName("title_screen_path") val titleScreenPath: String? = null,
    @SerialName("marquee_path") val marqueePath: String? = null,
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
data class CollectionDto(
    val id: Int,
    val name: String = "",
    @SerialName("rom_ids") val romIds: List<Int> = emptyList(),
    @SerialName("rom_count") val romCount: Int = 0,
)

@Serializable
data class VirtualCollectionDto(
    val id: String,
    val name: String = "",
    val type: String? = null,
    @SerialName("rom_ids") val romIds: List<Int> = emptyList(),
    @SerialName("rom_count") val romCount: Int = 0,
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
