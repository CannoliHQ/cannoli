package dev.cannoli.scorza.romm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RommLibrary {
    suspend fun platforms(): List<RommPlatform>
    suspend fun games(platform: RommPlatform, page: Int, search: String? = null): RommPage<RommGame>
    suspend fun searchAll(query: RommSearchQuery): List<RommGame>
    suspend fun collections(groups: Set<RommCollectionGroup>): List<RommCollection>

    companion object {
        const val PAGE_SIZE = 100
    }
}

class LiveRommLibrary(
    private val client: RommClient,
    private val platformMap: PlatformMap,
) : RommLibrary {

    override suspend fun platforms(): List<RommPlatform> =
        withContext(Dispatchers.IO) {
            platformMap.toDomain(client.getPlatforms())
        }

    override suspend fun games(platform: RommPlatform, page: Int, search: String?): RommPage<RommGame> =
        withContext(Dispatchers.IO) {
            val limit = RommLibrary.PAGE_SIZE
            val offset = page * limit
            val dto = client.getRoms(platform.id, limit, offset, search)
            RommPage(
                items = dto.items.map { it.toDomain() },
                total = dto.total,
                limit = dto.limit,
                offset = dto.offset,
            )
        }

    override suspend fun searchAll(query: RommSearchQuery): List<RommGame> =
        withContext(Dispatchers.IO) {
            val term = query.text.trim()
            if (term.isEmpty()) return@withContext emptyList()
            client.getRoms(platformId = null, limit = 300, offset = 0, search = term)
                .items.map { it.toDomain() }
        }

    override suspend fun collections(groups: Set<RommCollectionGroup>): List<RommCollection> =
        withContext(Dispatchers.IO) {
            groups.flatMap { group ->
                runCatching { client.getCollections(group) }.getOrDefault(emptyList())
                    .map { RommCollection(it.id, group, it.name, it.romCount) }
            }
        }
}

internal fun SimpleRomDto.toDomain(): RommGame = RommGame(
    id = id,
    platformId = platformId,
    name = name?.ifEmpty { null } ?: fsNameNoExt.ifEmpty { fsName },
    fsName = fsName,
    sizeBytes = fsSizeBytes,
    summary = summary,
    revision = revision,
    regions = regions,
    languages = languages,
    coverPath = pathCoverLarge ?: urlCover,
    files = files.map { RommFile(it.fileName, it.fileSizeBytes, it.crcHash, it.md5Hash, it.sha1Hash) },
    companies = metadatum?.companies ?: emptyList(),
    genres = metadatum?.genres ?: emptyList(),
    gameModes = metadatum?.gameModes ?: emptyList(),
    firstReleaseDate = metadatum?.firstReleaseDate,
    ssMedia = ssMetadata?.let {
        RommSsMedia(it.box2dUrl, it.box3dUrl, it.miximageUrl, it.titleScreenUrl, it.screenshotUrl, it.marqueeUrl, it.manualUrl)
    },
)

internal fun FirmwareDto.toDomain() = RommFirmware(
    id = id, fileName = fileName, sizeBytes = fileSizeBytes,
    md5 = md5Hash, sha1 = sha1Hash, crc = crcHash,
)
