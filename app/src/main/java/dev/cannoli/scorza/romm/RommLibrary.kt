package dev.cannoli.scorza.romm

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
    coverPath = pathCoverLarge,
    files = files.map { RommFile(it.fileName, it.fileSizeBytes, it.crcHash, it.md5Hash, it.sha1Hash, it.id, rommFileSubDir(it.filePath, fullPath)) },
    companies = metadatum?.companies ?: emptyList(),
    genres = metadatum?.genres ?: emptyList(),
    gameModes = metadatum?.gameModes ?: emptyList(),
    firstReleaseDate = metadatum?.firstReleaseDate,
    ssMedia = ssMetadata?.let {
        RommSsMedia(it.box3dPath, it.miximagePath, it.titleScreenPath, it.marqueePath)
    },
    screenshotPath = mergedScreenshots.firstOrNull(),
    hasManual = hasManual,
    manualPath = pathManual,
    groupKey = (siblings.map { it.id } + id).min(),
    isMainSibling = isMainSibling,
)

internal fun rommFileSubDir(filePath: String, romFullPath: String): String {
    if (romFullPath.isEmpty() || filePath == romFullPath) return ""
    if (!filePath.startsWith("$romFullPath/")) return ""
    return filePath.removePrefix("$romFullPath/").trim('/')
}

internal fun FirmwareDto.toDomain() = RommFirmware(
    id = id, fileName = fileName, sizeBytes = fileSizeBytes,
    md5 = md5Hash, sha1 = sha1Hash, crc = crcHash,
)
