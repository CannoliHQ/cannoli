package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommCollection
import dev.cannoli.scorza.romm.RommCollectionGroup
import dev.cannoli.scorza.romm.RommFirmware
import dev.cannoli.scorza.romm.RommFoldedGame
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommLibrary
import dev.cannoli.scorza.romm.RommLocalState
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RommSearchQuery
import dev.cannoli.scorza.romm.RommVirtualType
import dev.cannoli.scorza.romm.cache.RommDatabase
import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.util.sortedNatural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class RommGameRow(
    val game: RommGame,
    val localState: LocalState,
    val groupKey: Int = game.groupKey,
    val versionCount: Int = 1,
    val anyPresent: Boolean = localState == LocalState.PRESENT,
)

data class LoadedRows<ID, T>(val id: ID, val rows: List<T>, val search: String? = null)

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
    private val serverUnsupported: () -> Boolean = { false },
) {

    fun isServerUnsupported(): Boolean = serverUnsupported()

    data class RommCollectionGameRow(
        val game: RommGame,
        val localState: LocalState,
        val platform: RommPlatform,
        val groupKey: Int = game.groupKey,
        val versionCount: Int = 1,
        val anyPresent: Boolean = localState == LocalState.PRESENT,
    )

    private val _platforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val platforms: StateFlow<List<RommPlatform>> = _platforms

    private val _allPlatforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val allPlatforms: StateFlow<List<RommPlatform>> = _allPlatforms

    private val _games = MutableStateFlow<LoadedRows<Int, RommGameRow>?>(null)
    val games: StateFlow<LoadedRows<Int, RommGameRow>?> = _games

    private val _searchResults = MutableStateFlow<LoadedRows<String, RommGameRow>?>(null)
    val searchResults: StateFlow<LoadedRows<String, RommGameRow>?> = _searchResults

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
    private var searchTerm: String? = null

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
        searchTerm = term
        _games.value = LoadedRows(platform.id, foldRows(platform, term), term)
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

    suspend fun loadCollections() {
        _collections.value = library.collections(enabledCollectionGroups())
    }

    fun hasAnyCollections(): Boolean = _collections.value.isNotEmpty()

    sealed interface CollectionEntry {
        object Landing : CollectionEntry
        data class Group(val group: RommCollectionGroup) : CollectionEntry
        object VirtualTypes : CollectionEntry
    }

    private val _groupCounts = MutableStateFlow<Map<RommCollectionGroup, Int>>(emptyMap())
    val groupCounts: StateFlow<Map<RommCollectionGroup, Int>> = _groupCounts

    private val _virtualTypeCounts = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val virtualTypeCounts: StateFlow<List<Pair<String, Int>>> = _virtualTypeCounts

    private val _collectionList = MutableStateFlow<LoadedRows<String, RommCollection>?>(null)
    val collectionList: StateFlow<LoadedRows<String, RommCollection>?> = _collectionList

    fun enabledGroups(): Set<RommCollectionGroup> = enabledCollectionGroups()

    suspend fun loadCollectionCounts() {
        val (groups, types) = withContext(Dispatchers.IO) {
            library.collectionGroupCounts() to library.virtualTypeCounts()
        }
        _groupCounts.value = groups
        _virtualTypeCounts.value = RommVirtualType.orderedFrom(types.keys).map { it to (types[it] ?: 0) }
    }

    fun collectionEntryTarget(): CollectionEntry {
        val enabled = enabledCollectionGroups()
        return when {
            enabled.size >= 2 -> CollectionEntry.Landing
            enabled.size == 1 -> enabled.single().let {
                if (it == RommCollectionGroup.VIRTUAL) CollectionEntry.VirtualTypes else CollectionEntry.Group(it)
            }
            else -> CollectionEntry.Landing
        }
    }

    suspend fun openCollections(group: RommCollectionGroup, virtualType: String? = null) {
        val key = group.name + (virtualType?.let { ":$it" } ?: "")
        val rows = withContext(Dispatchers.IO) { library.collections(setOf(group), virtualType) }
        _collectionList.value = LoadedRows(key, rows)
    }

    suspend fun openCollection(collection: RommCollection, search: String? = null) {
        val database = db ?: return
        _collectionGames.value = LoadedRows(collection.id, foldCollectionRows(database, collection.id, search), search)
    }

    private suspend fun foldCollectionRows(database: RommDatabase, collectionId: String, search: String?): List<RommCollectionGameRow> {
        val platformsById = withContext(Dispatchers.IO) { database.platforms().associateBy { it.id } }
        val folded = library.foldedGamesForCollection(collectionId, search)
        val linkedIds = linkedIdsProvider()
        val presentByTag = mutableMapOf<String, Set<String>>()
        return folded.mapNotNull { f ->
            val platform = platformsById[f.game.platformId] ?: return@mapNotNull null
            val present = presentByTag.getOrPut(platform.cannoliTag) { presentNamesFor(platform.cannoliTag) }
            RommCollectionGameRow(
                game = f.game,
                localState = localStateOf(f.game, linkedIds, present),
                platform = platform,
                groupKey = f.game.groupKey,
                versionCount = f.variantCount,
                anyPresent = anyPresent(f, linkedIds, present),
            )
        }
    }

    suspend fun refreshCollectionLocalState() {
        val database = db ?: return
        val loaded = _collectionGames.value ?: return
        if (loaded.rows.isEmpty()) return
        _collectionGames.value = loaded.copy(rows = foldCollectionRows(database, loaded.id, loaded.search))
    }

    suspend fun refreshLocalState() {
        val platform = current ?: return
        val loaded = _games.value ?: return
        if (loaded.rows.isEmpty()) return
        // Re-fold so folded-group present state reflects the just-completed download.
        _games.value = loaded.copy(rows = foldRows(platform, searchTerm))
    }

    suspend fun loadGlobalSearch(query: RommSearchQuery) {
        if (_allPlatforms.value.isEmpty()) loadPlatforms()
        val folded = library.foldedGlobalSearch(query)
        val rows = withContext(Dispatchers.IO) {
            val platformsById = _allPlatforms.value.associateBy { it.id }
            val linkedIds = linkedIdsProvider()
            val presentByTag = mutableMapOf<String, Set<String>>()
            folded.map { f ->
                val tag = platformsById[f.game.platformId]?.cannoliTag
                val present = tag?.let { presentByTag.getOrPut(it) { presentNamesFor(it) } } ?: emptySet()
                toRow(f, linkedIds, present)
            }
        }
        _searchResults.value = LoadedRows(query.text, rows)
    }

    data class RommFirmwareRow(val firmware: RommFirmware, val present: Boolean)

    suspend fun loadFirmware(platformId: Int, tag: String): List<RommFirmwareRow> = withContext(Dispatchers.IO) {
        val biosDir = biosDirFor(tag)
        firmwareFor(platformId)
            .map { RommFirmwareRow(it, java.io.File(biosDir, it.fileName).exists()) }
    }

    fun isMultiSelect(): Boolean = _multiSelect.value

    enum class MultiSelectSource { PLATFORM, COLLECTION }

    private var multiSelectSource = MultiSelectSource.PLATFORM

    fun enterMultiSelect(source: MultiSelectSource, preCheckId: Int?) {
        if (_multiSelect.value) return
        multiSelectSource = source
        _multiSelect.value = true
        _checkedIds.value = if (preCheckId != null && isCheckable(preCheckId)) setOf(preCheckId) else emptySet()
    }

    fun toggleChecked(id: Int) {
        if (!_multiSelect.value || !isCheckable(id)) return
        _checkedIds.value = if (id in _checkedIds.value) _checkedIds.value - id else _checkedIds.value + id
    }

    fun confirmMultiSelect(): Set<Int> {
        val ids = _checkedIds.value
        cancelMultiSelect()
        return ids
    }

    fun cancelMultiSelect() {
        _multiSelect.value = false
        _checkedIds.value = emptySet()
    }

    private fun checkableCandidates(): List<Pair<Int, LocalState>> = when (multiSelectSource) {
        MultiSelectSource.PLATFORM -> (_games.value?.rows ?: emptyList()).map { it.game.id to it.localState }
        MultiSelectSource.COLLECTION -> (_collectionGames.value?.rows ?: emptyList()).map { it.game.id to it.localState }
    }

    private fun isCheckable(id: Int): Boolean =
        checkableCandidates().any { it.first == id && it.second == LocalState.REMOTE }

    private fun localStateOf(game: RommGame, linkedIds: Set<Int>, present: Set<String>): LocalState =
        if (game.id in linkedIds) LocalState.PRESENT
        else RommLocalState.of(game.fsName, present)

    private suspend fun foldRows(platform: RommPlatform, search: String?): List<RommGameRow> =
        withContext(Dispatchers.IO) {
            val folded = library.foldedGames(platform, search)
            val linkedIds = linkedIdsProvider()
            val present = presentNamesFor(platform.cannoliTag)
            folded.map { toRow(it, linkedIds, present) }
        }

    private fun toRow(folded: RommFoldedGame, linkedIds: Set<Int>, present: Set<String>): RommGameRow =
        RommGameRow(
            game = folded.game,
            localState = localStateOf(folded.game, linkedIds, present),
            groupKey = folded.game.groupKey,
            versionCount = folded.variantCount,
            anyPresent = anyPresent(folded, linkedIds, present),
        )

    // The whole group shares the representative's platform (RomM siblings are per-platform), so
    // present detection uses that platform's linked ids + present filenames for every member.
    private fun anyPresent(folded: RommFoldedGame, linkedIds: Set<Int>, present: Set<String>): Boolean =
        folded.memberIds.any { it in linkedIds } ||
            folded.memberFsNames.any { RommLocalState.of(it, present) == LocalState.PRESENT }

    fun presentIdsForTag(tag: String, games: List<RommGame>): Set<Int> {
        val linkedIds = linkedIdsProvider()
        val present = presentNamesFor(tag)
        return games.filter { localStateOf(it, linkedIds, present) == LocalState.PRESENT }.map { it.id }.toSet()
    }

    suspend fun groupMembers(groupKey: Int): List<RommGame> = library.groupMembers(groupKey)
}
