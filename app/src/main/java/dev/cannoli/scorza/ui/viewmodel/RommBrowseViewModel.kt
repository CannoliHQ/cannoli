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

data class LoadedRows<ID, T>(val id: ID, val rows: List<T>)

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

    private val _games = MutableStateFlow<LoadedRows<Int, RommGameRow>?>(null)
    val games: StateFlow<LoadedRows<Int, RommGameRow>?> = _games

    private val _searchResults = MutableStateFlow<List<RommGameRow>>(emptyList())
    val searchResults: StateFlow<List<RommGameRow>> = _searchResults

    private val _collections = MutableStateFlow<List<RommCollection>>(emptyList())
    val collections: StateFlow<List<RommCollection>> = _collections

    private val _collectionGames = MutableStateFlow<LoadedRows<String, RommCollectionGameRow>?>(null)
    val collectionGames: StateFlow<LoadedRows<String, RommCollectionGameRow>?> = _collectionGames

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
        _games.value = LoadedRows(platform.id, rows)
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
        _platforms.value = emptyList()
        _collections.value = emptyList()
        _games.value = null
        _collectionGames.value = null
        syncCoordinator?.syncFull()
        loadPlatforms()
        loadCollections()
        reload()
    }

    suspend fun loadMore() {
        if (!hasMore) return
        val platform = current ?: return
        val loaded = _games.value ?: return
        page += 1
        _games.value = loaded.copy(rows = loaded.rows + loadPage(platform, page, searchTerm))
    }

    suspend fun loadCollections() {
        _collections.value = library.collections(enabledCollectionGroups())
    }

    fun hasAnyCollections(): Boolean = _collections.value.isNotEmpty()

    fun flattenedCollections(): List<RommCollection> =
        RommCollectionGroup.entries.flatMap { group -> _collections.value.filter { it.group == group } }

    suspend fun openCollection(collection: RommCollection, search: String? = null) {
        val database = db ?: return
        currentCollectionId = collection.id
        collectionPage = 0
        collectionSearchTerm = search
        val (rows, hasMore) = withContext(Dispatchers.IO) {
            val platformsById = database.platforms().associateBy { it.id }
            val games = database.gamesForCollection(collection.id, search, collectionPageSize, 0)
            val linkedIds = linkedIdsProvider()
            val presentByTag = presentNamesByTag(games, platformsById)
            val mapped = games.mapNotNull { game ->
                val platform = platformsById[game.platformId] ?: return@mapNotNull null
                RommCollectionGameRow(game, localStateOf(game, linkedIds, presentByTag[platform.cannoliTag] ?: emptySet()), platform)
            }
            mapped to (games.size == collectionPageSize)
        }
        collectionHasMore = hasMore
        _collectionGames.value = LoadedRows(collection.id, rows)
    }

    suspend fun loadMoreCollection() {
        if (!collectionHasMore) return
        val id = currentCollectionId ?: return
        val database = db ?: return
        val loaded = _collectionGames.value ?: return
        collectionPage += 1
        val offset = collectionPage * collectionPageSize
        val (rows, hasMore) = withContext(Dispatchers.IO) {
            val platformsById = database.platforms().associateBy { it.id }
            val games = database.gamesForCollection(id, collectionSearchTerm, collectionPageSize, offset)
            val linkedIds = linkedIdsProvider()
            val presentByTag = presentNamesByTag(games, platformsById)
            val mapped = games.mapNotNull { game ->
                val platform = platformsById[game.platformId] ?: return@mapNotNull null
                RommCollectionGameRow(game, localStateOf(game, linkedIds, presentByTag[platform.cannoliTag] ?: emptySet()), platform)
            }
            mapped to (games.size == collectionPageSize)
        }
        collectionHasMore = hasMore
        _collectionGames.value = loaded.copy(rows = loaded.rows + rows)
    }

    suspend fun refreshLocalState() {
        val platform = current ?: return
        val loaded = _games.value ?: return
        if (loaded.rows.isEmpty()) return
        val updated = withContext(Dispatchers.IO) {
            val linkedIds = linkedIdsProvider()
            val present = presentNamesFor(platform.cannoliTag)
            loaded.rows.map { row -> row.copy(localState = localStateOf(row.game, linkedIds, present)) }
        }
        _games.value = loaded.copy(rows = updated)
    }

    suspend fun loadGlobalSearch(query: RommSearchQuery) {
        if (_allPlatforms.value.isEmpty()) loadPlatforms()
        val games = library.searchAll(query)
        _searchResults.value = withContext(Dispatchers.IO) {
            val platformsById = _allPlatforms.value.associateBy { it.id }
            val linkedIds = linkedIdsProvider()
            val presentByTag = presentNamesByTag(games, platformsById)
            games.sortedNatural { it.name }.map { g ->
                val tag = platformsById[g.platformId]?.cannoliTag
                RommGameRow(g, localStateOf(g, linkedIds, presentByTag[tag] ?: emptySet()))
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
        val games = (_games.value?.rows ?: emptyList()).filter { it.game.id in ids }.map { it.game }
        cancelMultiSelect()
        return games
    }

    fun cancelMultiSelect() {
        _multiSelect.value = false
        _checkedIds.value = emptySet()
    }

    private fun isCheckable(id: Int): Boolean =
        (_games.value?.rows ?: emptyList()).any { it.game.id == id && it.localState == LocalState.REMOTE }

    private fun localStateOf(game: RommGame, linkedIds: Set<Int>, present: Set<String>): LocalState =
        if (game.id in linkedIds) LocalState.PRESENT
        else RommLocalState.of(game.fsName, present)

    private fun presentNamesByTag(games: List<RommGame>, platformsById: Map<Int, RommPlatform>): Map<String, Set<String>> =
        games.mapNotNull { platformsById[it.platformId]?.cannoliTag }.toSet().associateWith { presentNamesFor(it) }

    private suspend fun loadPage(platform: RommPlatform, page: Int, search: String?): List<RommGameRow> {
        val pageData = library.games(platform, page, search)
        hasMore = pageData.hasMore
        return withContext(Dispatchers.IO) {
            val linkedIds = linkedIdsProvider()
            val present = presentNamesFor(platform.cannoliTag)
            pageData.items
                .sortedNatural { it.name }
                .map { game -> RommGameRow(game, localStateOf(game, linkedIds, present)) }
        }
    }
}
