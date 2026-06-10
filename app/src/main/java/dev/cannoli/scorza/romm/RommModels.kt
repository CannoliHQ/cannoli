package dev.cannoli.scorza.romm

data class RommPlatform(
    val id: Int,
    val slug: String,
    val cannoliTag: String,
    val displayName: String,
    val romCount: Int,
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
)

data class RommPage<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
) {
    val hasMore: Boolean get() = offset + items.size < total
}
