package dev.cannoli.scorza.romm

import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RommLibrary {
    suspend fun platforms(): List<RommPlatform>
    suspend fun games(platform: RommPlatform, page: Int, search: String? = null): RommPage<RommGame>
    suspend fun foldedGames(platform: RommPlatform, search: String? = null): List<RommFoldedGame>
    suspend fun foldedGamesForCollection(collectionId: String, search: String? = null): List<RommFoldedGame>
    suspend fun foldedGlobalSearch(query: RommSearchQuery): List<RommFoldedGame>
    suspend fun groupMembers(groupKey: Int): List<RommGame>
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

    // LiveRommLibrary is not the browse runtime; the folded reads only exist so the interface stays
    // consistent. Browse always goes through CachedRommLibrary, which uses the DB window fold.
    override suspend fun foldedGames(platform: RommPlatform, search: String?): List<RommFoldedGame> =
        withContext(Dispatchers.IO) {
            val games = client.getRoms(platform.id, limit = platform.romCount.coerceAtLeast(1), offset = 0, search = null)
                .items.map { it.toDomain() }
                .filter { rommGameMatches(it, search) }
            foldLive(games)
        }

    override suspend fun foldedGamesForCollection(collectionId: String, search: String?): List<RommFoldedGame> =
        emptyList()

    override suspend fun foldedGlobalSearch(query: RommSearchQuery): List<RommFoldedGame> =
        withContext(Dispatchers.IO) { foldLive(searchAll(query)) }

    override suspend fun groupMembers(groupKey: Int): List<RommGame> = emptyList()

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

// Minimal live fold; mirrors the DB representative order (main sibling, region rank, natural name).
// Only used by LiveRommLibrary, which is not the browse runtime.
private fun foldLive(games: List<RommGame>): List<RommFoldedGame> =
    games.groupBy { it.platformId to if (it.groupKey > 0) it.groupKey else -it.id }
        .map { (_, members) ->
            val rep = members.minWithOrNull(
                compareByDescending<RommGame> { it.isMainSibling }
                    .thenBy { RommVariantFolder.regionRank(it.regions) }
                    .thenBy(NaturalSort) { it.name }
            ) ?: members.first()
            RommFoldedGame(rep, members.size, members.map { it.id }, members.map { it.fsName })
        }
        .sortedWith(compareBy(NaturalSort) { it.game.name })

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
