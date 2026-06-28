package dev.cannoli.scorza.romm

data class RommPlatform(
    val id: Int,
    val slug: String,
    val cannoliTag: String,
    val displayName: String,
    val romCount: Int,
    val firmwareCount: Int = 0,
)

data class RommFile(
    val fileName: String,
    val sizeBytes: Long,
    val crc: String?,
    val md5: String?,
    val sha1: String?,
)

data class RommGame(
    val id: Int,
    val platformId: Int,
    val name: String,
    val fsName: String,
    val sizeBytes: Long,
    val summary: String?,
    val revision: String?,
    val regions: List<String>,
    val languages: List<String>,
    val coverPath: String?,
    val files: List<RommFile>,
    val companies: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val gameModes: List<String> = emptyList(),
    val firstReleaseDate: Long? = null,
    val ssMedia: RommSsMedia? = null,
)

data class RommSsMedia(
    val box2d: String? = null,
    val box3d: String? = null,
    val mix: String? = null,
    val titleScreen: String? = null,
    val screenshot: String? = null,
    val marquee: String? = null,
    val manual: String? = null,
)

data class RommFirmware(
    val id: Int,
    val fileName: String,
    val sizeBytes: Long,
    val md5: String?,
    val sha1: String?,
    val crc: String?,
)

data class RommPage<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
) {
    val hasMore: Boolean get() = offset + items.size < total
}

data class RommNetworkCollection(
    val id: String,
    val group: RommCollectionGroup,
    val name: String,
    val romIds: List<Int>,
    val romCount: Int,
)

data class RommCollection(
    val id: String,
    val group: RommCollectionGroup,
    val name: String,
    val romCount: Int,
)
