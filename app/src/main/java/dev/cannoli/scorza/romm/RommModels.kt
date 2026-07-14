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
    val screenshotPath: String? = null,
    val hasManual: Boolean = false,
    val manualPath: String? = null,
    val groupKey: Int = 0,
    val isMainSibling: Boolean = false,
)

/**
 * Paths to the media RomM stores and serves itself. The provider's own urls are deliberately not
 * modelled: media is only ever read back from the RomM instance, never from ScreenScraper.
 */
data class RommSsMedia(
    val box3dPath: String? = null,
    val mixPath: String? = null,
    val titleScreenPath: String? = null,
    val marqueePath: String? = null,
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
    val virtualType: String? = null,
)

data class RommCollection(
    val id: String,
    val group: RommCollectionGroup,
    val name: String,
    val romCount: Int,
    val virtualType: String? = null,
)

data class RommFoldedGame(
    val game: RommGame,
    val variantCount: Int,
    val memberIds: List<Int>,
    val memberFsNames: List<String>,
)
