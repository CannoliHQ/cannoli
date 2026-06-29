package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommLocalState
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import dev.cannoli.scorza.romm.cache.RommDatabase
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
    private val db: RommDatabase?,
    private val presentNamesFor: (tag: String) -> Set<String>,
    private val linkedIdsProvider: () -> Set<Int>,
    private val hiddenTagsProvider: () -> Set<String> = { emptySet() },
    private val firmwareFor: (platformId: Int) -> List<RommFirmware> = { emptyList() },
    private val biosDirFor: (tag: String) -> java.io.File = { java.io.File("") },
    private val enabledCollectionGroups: () -> Set<RommCollectionGroup> = { setOf(RommCollectionGroup.USER) },
    private val collectionPageSize: Int = RommLibrary.PAGE_SIZE,
) {

    data class RommCollectionGameRow(val game: RommGame, val localState: LocalState, val platform: RommPlatform)

    private val _platforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val platforms: StateFlow<List<RommPlatform>> = _platforms

    private val _allPlatforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val allPlatforms: StateFlow<List<RommPlatform>> = _allPlatforms

    private val _games = MutableStateFlow<List<RommGameRow>>(emptyList())
    val games: StateFlow<List<RommGameRow>> = _games

    private val _searchResults = MutableStateFlow<List<RommGameRow>>(emptyList())
    val searchResults: StateFlow<List<RommGameRow>> = _searchResults

    private val _loadedPlatformId = MutableStateFlow<Int?>(null)
    val loadedPlatformId: StateFlow<Int?> = _loadedPlatformId

    private val _collections = MutableStateFlow<List<RommCollection>>(emptyList())
    val collections: StateFlow<List<RommCollection>> = _collections

    private val _collectionGames = MutableStateFlow<List<RommCollectionGameRow>>(emptyList())
    val collectionGames: StateFlow<List<RommCollectionGameRow>> = _collectionGames

    private val _loadedCollectionId = MutableStateFlow<String?>(null)
    val loadedCollectionId: StateFlow<String?> = _loadedCollectionId

    private val _multiSelect = MutableStateFlow(false)
    val multiSelect: StateFlow<Boolean> = _multiSelect

    private val _checkedIds = MutableStateFlow<Set<Int>>(emptySet())
    val checkedIds: StateFlow<Set<Int>> = _checkedIds

    val syncStatus: StateFlow<RommSyncCoordinator.SyncStatus> =
        syncCoordinator?.status ?: MutableStateFlow(RommSyncCoordinator.SyncStatus.IDLE)

    val syncProgress: StateFlow<RommSyncCoordinator.SyncProgress> =
        syncCoordinator?.progress ?: MutableStateFlow(RommSyncCoordinator.SyncProgress(0, 0))

    private var current: RommPlatform? = null
    private var page = 0
    private var hasMore = false
    private var searchTerm: String? = null

    private var currentCollectionId: String? = null
    private var collectionPage = 0
    private var collectionHasMore = false
    private var collectionSearchTerm: String? = null

    suspend fun loadPlatforms() {
        val sortedFull = library.platforms().sortedNatural { it.displayName }
        _allPlatforms.value = sortedFull
        val hidden = hiddenTagsProvider()
        _platforms.value = sortedFull.filterNot { it.cannoliTag in hidden }
    }

    suspend fun enterBrowse() {
        loadPlatforms()
        loadCollections()
        if (_platforms.value.isEmpty()) syncCoordinator?.syncFull() else syncCoordinator?.syncDelta()
        loadPlatforms()
        loadCollections()
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

    suspend fun reload() {
        val platform = current
        if (platform != null) openPlatform(platform, searchTerm) else loadPlatforms()
    }

    suspend fun refresh() {
        syncCoordinator?.syncDelta()
        reload()
    }

    suspend fun rebuild() {
        db?.clearAll()
        syncCoordinator?.syncFull()
        loadPlatforms()
        reload()
    }

    suspend fun loadMore() {
        if (!hasMore) return
        val platform = current ?: return
        page += 1
        _games.value = _games.value + loadPage(platform, page, searchTerm)
    }

    suspend fun loadCollections() {
        _collections.value = library.collections(enabledCollectionGroups())
    }

    fun hasAnyCollections(): Boolean = _collections.value.isNotEmpty()

    fun flattenedCollections(): List<RommCollection> =
        RommCollectionGroup.entries.flatMap { group -> _collections.value.filter { it.group == group } }

    suspend fun openCollection(collection: RommCollection, search: String? = null) {
        _loadedCollectionId.value = collection.id
        val database = db ?: return
        currentCollectionId = collection.id
        collectionPage = 0
        collectionSearchTerm = search
        val (rows, hasMore) = withContext(Dispatchers.IO) {
            val platformsById = database.platforms().associateBy { it.id }
            val games = database.gamesForCollection(collection.id, search, collectionPageSize, 0)
            val mapped = games.mapNotNull { game ->
                val platform = platformsById[game.platformId] ?: return@mapNotNull null
                RommCollectionGameRow(game, localStateFor(game, platform), platform)
            }
            mapped to (games.size == collectionPageSize)
        }
        collectionHasMore = hasMore
        _collectionGames.value = rows
    }

    suspend fun loadMoreCollection() {
        if (!collectionHasMore) return
        val id = currentCollectionId ?: return
        val database = db ?: return
        collectionPage += 1
        val offset = collectionPage * collectionPageSize
        val (rows, hasMore) = withContext(Dispatchers.IO) {
            val platformsById = database.platforms().associateBy { it.id }
            val games = database.gamesForCollection(id, collectionSearchTerm, collectionPageSize, offset)
            val mapped = games.mapNotNull { game ->
                val platform = platformsById[game.platformId] ?: return@mapNotNull null
                RommCollectionGameRow(game, localStateFor(game, platform), platform)
            }
            mapped to (games.size == collectionPageSize)
        }
        collectionHasMore = hasMore
        _collectionGames.value = _collectionGames.value + rows
    }

    suspend fun refreshLocalState() {
        val platform = current ?: return
        val rows = _games.value
        if (rows.isEmpty()) return
        val updated = withContext(Dispatchers.IO) {
            rows.map { row -> row.copy(localState = localStateFor(row.game, platform)) }
        }
        _games.value = updated
    }

    suspend fun loadGlobalSearch(query: RommSearchQuery) {
        if (_allPlatforms.value.isEmpty()) loadPlatforms()
        val games = library.searchAll(query)
        _searchResults.value = withContext(Dispatchers.IO) {
            val platformsById = _allPlatforms.value.associateBy { it.id }
            val linkedIds = linkedIdsProvider()
            val presentByTag = games
                .mapNotNull { platformsById[it.platformId]?.cannoliTag }
                .toSet()
                .associateWith { presentNamesFor(it) }
            games.sortedNatural { it.name }.map { g ->
                val tag = platformsById[g.platformId]?.cannoliTag
                val present = presentByTag[tag] ?: emptySet()
                val state = if (g.id in linkedIds) LocalState.PRESENT
                else RommLocalState.of(g.fsName, present)
                RommGameRow(g, state)
            }
        }
    }

    data class RommFirmwareRow(val firmware: RommFirmware, val present: Boolean)

    suspend fun loadFirmware(platformId: Int, tag: String): List<RommFirmwareRow> = withContext(Dispatchers.IO) {
        val biosDir = biosDirFor(tag)
        firmwareFor(platformId)
            .map { RommFirmwareRow(it, java.io.File(biosDir, it.fileName).exists()) }
    }

    fun isMultiSelect(): Boolean = _multiSelect.value

    fun enterMultiSelect(preCheckId: Int?) {
        if (_multiSelect.value) return
        _multiSelect.value = true
        _checkedIds.value = if (preCheckId != null && isCheckable(preCheckId)) setOf(preCheckId) else emptySet()
    }

    fun toggleChecked(id: Int) {
        if (!_multiSelect.value || !isCheckable(id)) return
        _checkedIds.value = if (id in _checkedIds.value) _checkedIds.value - id else _checkedIds.value + id
    }

    fun confirmMultiSelect(): List<RommGame> {
        val ids = _checkedIds.value
        val games = _games.value.filter { it.game.id in ids }.map { it.game }
        cancelMultiSelect()
        return games
    }

    fun cancelMultiSelect() {
        _multiSelect.value = false
        _checkedIds.value = emptySet()
    }

    private fun isCheckable(id: Int): Boolean =
        _games.value.any { it.game.id == id && it.localState == LocalState.REMOTE }

    private fun localStateFor(game: RommGame, platform: RommPlatform): LocalState {
        val linkedIds = linkedIdsProvider()
        return if (game.id in linkedIds) LocalState.PRESENT
        else RommLocalState.of(game.fsName, presentNamesFor(platform.cannoliTag))
    }

    private suspend fun loadPage(platform: RommPlatform, page: Int, search: String?): List<RommGameRow> {
        val pageData = library.games(platform, page, search)
        hasMore = pageData.hasMore
        return withContext(Dispatchers.IO) {
            pageData.items
                .sortedNatural { it.name }
                .map { game -> RommGameRow(game, localStateFor(game, platform)) }
        }
    }
}
