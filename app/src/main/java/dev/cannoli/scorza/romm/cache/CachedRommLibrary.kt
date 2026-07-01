package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommFoldedGame
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommPage
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CachedRommLibrary(private val db: RommDatabase) : RommLibrary {

    override suspend fun platforms(): List<RommPlatform> = withContext(Dispatchers.IO) { db.platforms() }

    override suspend fun games(platform: RommPlatform, page: Int, search: String?): RommPage<RommGame> =
        withContext(Dispatchers.IO) {
            val limit = RommLibrary.PAGE_SIZE
            val offset = page * limit
            RommPage(
                items = db.games(platform.id, search, limit, offset),
                total = db.gamesCount(platform.id, search),
                limit = limit,
                offset = offset,
            )
        }

    override suspend fun foldedGames(platform: RommPlatform, search: String?): List<RommFoldedGame> =
        withContext(Dispatchers.IO) { db.foldedGames(platform.id, search) }

    override suspend fun foldedGamesForCollection(collectionId: String, search: String?): List<RommFoldedGame> =
        withContext(Dispatchers.IO) { db.foldedGamesForCollection(collectionId, search) }

    override suspend fun foldedGlobalSearch(query: RommSearchQuery): List<RommFoldedGame> =
        withContext(Dispatchers.IO) { db.foldedGlobalSearch(query) }

    override suspend fun groupMembers(groupKey: Int): List<RommGame> =
        withContext(Dispatchers.IO) { db.groupMembers(groupKey) }

    override suspend fun searchAll(query: RommSearchQuery): List<RommGame> =
        withContext(Dispatchers.IO) { db.searchAllGames(query) }

    override suspend fun collections(groups: Set<RommCollectionGroup>, virtualType: String?): List<RommCollection> =
        withContext(Dispatchers.IO) { db.collections(groups, virtualType) }

    override suspend fun collectionGroupCounts(): Map<RommCollectionGroup, Int> =
        withContext(Dispatchers.IO) { db.collectionGroupCounts() }

    override suspend fun virtualTypeCounts(): Map<String, Int> =
        withContext(Dispatchers.IO) { db.virtualTypeCounts() }
}
