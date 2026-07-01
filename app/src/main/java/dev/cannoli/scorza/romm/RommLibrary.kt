package dev.cannoli.scorza.romm

import dev.cannoli.scorza.util.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RommLibrary {
    suspend fun platforms(): List<RommPlatform>
    suspend fun games(platform: RommPlatform, page: Int, search: String? = null): RommPage<RommGame>
    suspend fun foldedGames(platform: RommPlatform, search: String? = null): List<RommGroup>
    suspend fun searchAll(query: RommSearchQuery): List<RommGame>
    suspend fun collections(groups: Set<RommCollectionGroup>, virtualType: String? = null): List<RommCollection>
    suspend fun collectionGroupCounts(): Map<RommCollectionGroup, Int>
    suspend fun virtualTypeCounts(): Map<String, Int>

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

    override suspend fun foldedGames(platform: RommPlatform, search: String?): List<RommGroup> =
        withContext(Dispatchers.IO) {
            val games = client.getRoms(platform.id, limit = platform.romCount.coerceAtLeast(1), offset = 0, search = null)
                .items.map { it.toDomain() }
                .filter { rommGameMatches(it, search) }
            RommVariantFolder.foldSorted(games)
        }

    override suspend fun searchAll(query: RommSearchQuery): List<RommGame> =
        withContext(Dispatchers.IO) {
            val term = query.text.trim()
            if (term.isEmpty()) return@withContext emptyList()
            client.getRoms(platformId = null, limit = 300, offset = 0, search = term)
                .items.map { it.toDomain() }
        }

    override suspend fun collections(groups: Set<RommCollectionGroup>, virtualType: String?): List<RommCollection> =
        withContext(Dispatchers.IO) {
            groups.flatMap { group ->
                runCatching { client.getCollections(group) }.getOrDefault(emptyList())
                    .map { RommCollection(it.id, group, it.name, it.romCount, it.virtualType) }
                    .filter { virtualType == null || it.virtualType == virtualType }
            }
        }

    override suspend fun collectionGroupCounts(): Map<RommCollectionGroup, Int> = emptyMap()
    override suspend fun virtualTypeCounts(): Map<String, Int> = emptyMap()
}

internal fun rommGameMatches(game: RommGame, search: String?): Boolean {
    val term = search?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotEmpty() } ?: return true
    return TextNormalizer.normalize(game.name).contains(term)
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
    groupKey = (siblings.map { it.id } + id).min(),
    isMainSibling = isMainSibling,
)

internal fun FirmwareDto.toDomain() = RommFirmware(
    id = id, fileName = fileName, sizeBytes = fileSizeBytes,
    md5 = md5Hash, sha1 = sha1Hash, crc = crcHash,
)
