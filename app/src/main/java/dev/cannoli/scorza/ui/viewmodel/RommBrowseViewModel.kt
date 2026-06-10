package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalFile
import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommLocalState
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.util.sortedNatural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class RommGameRow(val game: RommGame, val localState: LocalState)

class RommBrowseViewModel(
    private val library: RommLibrary,
    private val syncCoordinator: RommSyncCoordinator?,
    private val localFilesFor: (tag: String) -> List<LocalFile>,
    private val linkedIdsProvider: () -> Set<Int>,
) {
    private val _platforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val platforms: StateFlow<List<RommPlatform>> = _platforms

    private val _games = MutableStateFlow<List<RommGameRow>>(emptyList())
    val games: StateFlow<List<RommGameRow>> = _games

    private val _loadedPlatformId = MutableStateFlow<Int?>(null)
    val loadedPlatformId: StateFlow<Int?> = _loadedPlatformId

    val syncStatus: StateFlow<RommSyncCoordinator.SyncStatus> =
        syncCoordinator?.status ?: MutableStateFlow(RommSyncCoordinator.SyncStatus.IDLE)

    val syncProgress: StateFlow<RommSyncCoordinator.SyncProgress> =
        syncCoordinator?.progress ?: MutableStateFlow(RommSyncCoordinator.SyncProgress(0, 0))

    private var current: RommPlatform? = null
    private var page = 0
    private var hasMore = false
    private var searchTerm: String? = null
    private var platformOrder: List<String> = emptyList()

    suspend fun loadPlatforms() {
        val order = platformOrder.withIndex().associate { (i, tag) -> tag.uppercase() to i }
        _platforms.value = library.platforms().sortedBy { order[it.cannoliTag.uppercase()] ?: Int.MAX_VALUE }
    }

    suspend fun enterBrowse(platformOrder: List<String> = emptyList()) {
        this.platformOrder = platformOrder
        loadPlatforms()
        if (_platforms.value.isEmpty()) syncCoordinator?.syncFull() else syncCoordinator?.syncDelta()
        loadPlatforms()
    }

    suspend fun openPlatform(platform: RommPlatform, search: String? = null) {
        val term = search?.ifBlank { null }
        current = platform
        page = 0
        searchTerm = term
        val rows = loadPage(platform, 0, term)
        _games.value = rows
        _loadedPlatformId.value = platform.id
    }

    suspend fun loadMore() {
        if (!hasMore) return
        val platform = current ?: return
        page += 1
        _games.value = _games.value + loadPage(platform, page, searchTerm)
    }

    private suspend fun loadPage(platform: RommPlatform, page: Int, search: String?): List<RommGameRow> {
        val pageData = library.games(platform, page, search)
        hasMore = pageData.hasMore
        return withContext(Dispatchers.IO) {
            val locals = localFilesFor(platform.cannoliTag)
            val linkedIds = linkedIdsProvider()
            pageData.items
                .sortedNatural { it.name }
                .map { game ->
                    val state = if (game.id in linkedIds) LocalState.PRESENT
                    else RommLocalState.of(game.fsName, game.sizeBytes, locals)
                    RommGameRow(game, state)
                }
        }
    }
}
