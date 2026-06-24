package dev.cannoli.scorza.romm.cache

import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
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

    override suspend fun searchAll(query: RommSearchQuery): List<RommGame> =
        withContext(Dispatchers.IO) { db.searchAllGames(query) }

    override suspend fun collections(groups: Set<RommCollectionGroup>): List<RommCollection> =
        withContext(Dispatchers.IO) { db.collections(groups) }
}
