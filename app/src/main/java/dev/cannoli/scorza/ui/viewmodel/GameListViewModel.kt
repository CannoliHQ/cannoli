package dev.cannoli.scorza.ui.viewmodel

import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.GameSearchQuery
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.util.TextNormalizer
import dev.cannoli.ui.components.OsdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal fun matchesSearch(item: ListItem, term: String): Boolean {
    val name = when (item) {
        is ListItem.RomItem -> item.rom.displayName
        is ListItem.AppItem -> item.app.displayName
        is ListItem.SubfolderItem -> item.name
        is ListItem.CollectionItem -> item.collection.displayName
        is ListItem.ChildCollectionItem -> item.collection.displayName
    }
    return TextNormalizer.normalize(name).contains(TextNormalizer.normalize(term))
}

internal fun applyItemFilter(items: List<ListItem>, term: String?): List<ListItem> =
    if (term.isNullOrBlank()) items else items.filter { matchesSearch(it, term) }

internal fun globalOriginTag(
    item: ListItem,
    toolsLabel: String,
    portsLabel: String,
    collectionLabel: String,
): String? = when (item) {
    is ListItem.RomItem -> item.rom.platformTag.uppercase()
    is ListItem.AppItem -> if (item.app.type == AppType.TOOL) toolsLabel else portsLabel
    is ListItem.CollectionItem -> collectionLabel
    else -> null
}

internal fun stableIdOf(item: ListItem?): String? = when (item) {
    is ListItem.RomItem -> "rom:${item.rom.id}"
    is ListItem.AppItem -> "app:${item.app.id}"
    is ListItem.ChildCollectionItem -> "col:${item.collection.id}"
    is ListItem.CollectionItem -> "col:${item.collection.id}"
    is ListItem.SubfolderItem -> "sub:${item.name}"
    else -> null
}

internal fun findIndexById(items: List<ListItem>, priorId: String?): Int? =
    priorId?.let { id -> items.indexOfFirst { stableIdOf(it) == id }.takeIf { it >= 0 } }

internal fun reloadPosition(
    items: List<ListItem>,
    preserveId: String?,
    preserveIndex: Int,
    preserveScroll: Int,
    prevCount: Int,
): Pair<Int, Int> {
    if (items.isEmpty()) return 0 to 0
    // Found the same item again (e.g. after a rename re-sorts the list): keep it selected but
    // do NOT force a scroll position. A forced scrollTarget would slam the item to the top of
    // the viewport; -1 lets the selection effect keep it on screen only if it actually moved off.
    findIndexById(items, preserveId)?.let { return it to -1 }
    val maxIdx = items.lastIndex
    val sameSize = prevCount >= 0 && items.size == prevCount && prevCount > 0
    return if (sameSize || prevCount < 0) {
        preserveIndex.coerceAtMost(maxIdx) to preserveScroll.coerceAtMost(maxIdx)
    } else 0 to 0
}

@ActivityScoped
class GameListViewModel @Inject constructor(
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val platformConfig: PlatformConfig,
    private val scanScheduler: ScanScheduler,
    private val cannoliPaths: CannoliPathsProvider,
    private val osdController: OsdController,
    @ApplicationContext private val context: android.content.Context,
) {
    private val resources: android.content.res.Resources get() = context.resources
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var showFavoriteStars: Boolean = true

    data class State(
        val platformTag: String = "",
        val platformTags: List<String> = emptyList(),
        val breadcrumb: String = "",
        val items: List<ListItem> = emptyList(),
        val allItems: List<ListItem> = emptyList(),
        val searchTerm: String? = null,
        val favoriteRomIds: Set<Long> = emptySet(),
        val favoriteAppIds: Set<Long> = emptySet(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val subfolderPath: String? = null,
        val isLoading: Boolean = true,
        val isCollection: Boolean = false,
        val isFavorites: Boolean = false,
        val collectionName: String? = null,
        val collectionId: Long? = null,
        val isCollectionsList: Boolean = false,
        val isGlobalSearch: Boolean = false,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        val multiSelectMode: Boolean = false,
        val checkedIndices: Set<Int> = emptySet()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var pendingRescan: ScanScheduler.ScanResult? = null
    private var applyRescanJob: Job? = null

    init {
        scope.launch {
            scanScheduler.results.collectLatest { result ->
                handleRescanResult(result)
            }
        }
    }

    private fun handleRescanResult(result: ScanScheduler.ScanResult) {
        val current = _state.value
        val activeTags = current.platformTags.map { it.uppercase() }
        if (result.platformTag !in activeTags) return
        if (current.reorderMode || current.multiSelectMode) {
            pendingRescan = result
            return
        }
        applyRescan(result)
    }

    private fun applyRescan(result: ScanScheduler.ScanResult) {
        applyRescanJob?.cancel()
        applyRescanJob = scope.launch(Dispatchers.IO) {
            val snapshot = _state.value
            val items = loadPlatformItems(snapshot.platformTag, snapshot.platformTags, snapshot.subfolderPath)
            val priorId = stableIdOf(snapshot.items.getOrNull(snapshot.selectedIndex))
            val (newIndex, newScroll) = reloadPosition(
                items, priorId, snapshot.selectedIndex, snapshot.selectedIndex, prevCount = -1
            )
            _state.update { live ->
                live.copy(
                    items = items,
                    allItems = items,
                    searchTerm = null,
                    favoriteRomIds = collectionsRepository.favoriteRomIds(),
                    favoriteAppIds = collectionsRepository.favoriteAppIds(),
                    selectedIndex = newIndex,
                    scrollTarget = newScroll,
                )
            }
            if (!result.silent) {
                withContext(Dispatchers.Main) {
                    osdController.show("Game list updated")
                }
            }
        }
    }

    var firstVisibleIndex: Int = 0

    private data class SavedPos(val id: String?, val index: Int, val scroll: Int)

    private val breadcrumbStack = mutableListOf<String>()
    private val indexStack = mutableListOf<Pair<Int, Int>>()
    private var collectionsListSaved = SavedPos(null, 0, 0)
    private var collectionsListItemCount: Int = 0
    private val collectionStack = mutableListOf<Triple<Long, Int, Int>>()

    fun savePosition(scrollIdx: Int = firstVisibleIndex) {
        _state.update { it.copy(scrollTarget = scrollIdx) }
    }

    fun saveCollectionsPosition() {
        val current = _state.value
        if (current.isCollectionsList) {
            val id = stableIdOf(current.items.getOrNull(current.selectedIndex))
            collectionsListSaved = SavedPos(id, current.selectedIndex, firstVisibleIndex)
            collectionsListItemCount = current.items.size
        }
    }

    fun loadPlatform(tag: String, tags: List<String> = listOf(tag), onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val items = loadPlatformItems(tag, tags, null)
                val displayName = platformConfig.getDisplayName(tag)
                val favRoms = collectionsRepository.favoriteRomIds()
                _state.value = State(
                    platformTag = tag,
                    platformTags = tags,
                    breadcrumb = displayName,
                    items = items,
                    allItems = items,
                    selectedIndex = 0,
                    favoriteRomIds = favRoms,
                    isLoading = false
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun loadCollectionById(id: Long, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollectionsList) {
            val savedId = stableIdOf(current.items.getOrNull(current.selectedIndex))
            collectionsListSaved = SavedPos(savedId, current.selectedIndex, firstVisibleIndex)
            collectionsListItemCount = current.items.size
        }
        breadcrumbStack.clear()
        indexStack.clear()
        collectionStack.clear()
        scope.launch(Dispatchers.IO) {
            loadCollectionByIdInternal(id, onReady)
        }
    }

    fun loadFavorites(onReady: () -> Unit = {}) {
        val id = collectionsRepository.favoritesId()
        if (id == null) {
            onReady()
            return
        }
        loadCollectionById(id, onReady)
    }

    fun enterChildCollectionById(id: Long, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollection && current.collectionId != null) {
            collectionStack.add(Triple(current.collectionId, current.selectedIndex, firstVisibleIndex))
        }
        scope.launch(Dispatchers.IO) {
            loadCollectionByIdInternal(id, onReady)
        }
    }

    fun exitChildCollection(onReady: () -> Unit = {}): Boolean {
        if (collectionStack.isEmpty()) return false
        val (parentId, parentIndex, parentScroll) = collectionStack.removeAt(collectionStack.lastIndex)
        scope.launch(Dispatchers.IO) {
            loadCollectionByIdInternal(parentId) {
                _state.update { it.copy(selectedIndex = parentIndex, scrollTarget = parentScroll) }
                onReady()
            }
        }
        return true
    }

    private suspend fun loadCollectionByIdInternal(collectionId: Long, onReady: () -> Unit) {
        val row = collectionsRepository.byId(collectionId)
        if (row == null) {
            withContext(Dispatchers.Main) { onReady() }
            return
        }
        val children = collectionsRepository.children(collectionId)
        val childItems = children.map { ListItem.ChildCollectionItem(Collection(it.id, it.displayName)) }
        val romIds = collectionsRepository.romIdsIn(collectionId)
        val appIds = collectionsRepository.appIdsIn(collectionId)
        val romItems = romIds.mapNotNull { romsRepository.gameById(it) }.map { ListItem.RomItem(it) }
        val appItems = appIds.mapNotNull { appsRepository.byId(it) }.map { ListItem.AppItem(it) }
        val combined = childItems + romItems + appItems
        val isFavorites = row.type == CollectionType.FAVORITES
        val items = if (isFavorites) combined else sortFavoritesFirst(combined)
        val parent = row.parentId?.let { collectionsRepository.byId(it) }
        val breadcrumb = if (parent != null) "/${row.displayName}" else row.displayName
        _state.value = State(
            breadcrumb = breadcrumb,
            items = items,
            allItems = items,
            favoriteRomIds = collectionsRepository.favoriteRomIds(),
            favoriteAppIds = collectionsRepository.favoriteAppIds(),
            selectedIndex = 0,
            isLoading = false,
            isCollection = true,
            isFavorites = isFavorites,
            collectionName = row.displayName,
            collectionId = collectionId,
        )
        withContext(Dispatchers.Main) { onReady() }
    }

    fun loadApkList(type: String, displayName: String, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val appType = if (type == "tools") AppType.TOOL else AppType.PORT
                val apps = appsRepository.all(appType)
                val favAppIds = if (showFavoriteStars) collectionsRepository.favoriteAppIds() else emptySet()
                val items = apps
                    .sortedBy { it.id !in favAppIds }
                    .map { ListItem.AppItem(it) }
                _state.value = State(
                    platformTag = type,
                    breadcrumb = displayName,
                    items = items,
                    allItems = items,
                    favoriteAppIds = favAppIds,
                    selectedIndex = 0,
                    isLoading = false
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun loadRecentlyPlayed(onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val entries = recentlyPlayedRepository.recent(limit = 15)
                val items = entries.mapNotNull { entry ->
                    when (val ref = entry.ref) {
                        is LibraryRef.Rom -> romsRepository.gameById(ref.id)?.let { ListItem.RomItem(it) }
                        is LibraryRef.App -> appsRepository.byId(ref.id)?.let { ListItem.AppItem(it) }
                    }
                }
                _state.value = State(
                    platformTag = "recently_played",
                    breadcrumb = resources.getString(R.string.label_recently_played),
                    items = items,
                    allItems = items,
                    favoriteRomIds = collectionsRepository.favoriteRomIds(),
                    favoriteAppIds = collectionsRepository.favoriteAppIds(),
                    selectedIndex = 0,
                    isLoading = false
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun loadCollectionsList(restoreIndex: Boolean = false, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        collectionStack.clear()
        scope.launch(Dispatchers.IO) {
            val collections = collectionsRepository.topLevel()
            val items = collections.map { row ->
                ListItem.CollectionItem(Collection(row.id, row.displayName))
            }
            val (idx, scroll) = if (restoreIndex && collectionsListItemCount > 0 && items.isNotEmpty()) {
                val maxIdx = items.lastIndex.coerceAtLeast(0)
                val found = findIndexById(items, collectionsListSaved.id)
                if (found != null) found to -1
                else collectionsListSaved.index.coerceAtMost(maxIdx) to collectionsListSaved.scroll.coerceAtMost(maxIdx)
            } else 0 to 0
            _state.value = State(
                breadcrumb = resources.getString(R.string.label_collections),
                items = items,
                allItems = items,
                selectedIndex = idx,
                scrollTarget = scroll,
                isLoading = false,
                isCollectionsList = true
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    fun loadGlobalSearch(query: GameSearchQuery, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val term = query.text.trim()
                val games = romsRepository.searchAllGames(query).map { ListItem.RomItem(it) }
                val apps = appsRepository.all()
                    .filter { TextNormalizer.normalize(it.displayName).contains(TextNormalizer.normalize(term)) }
                    .map { ListItem.AppItem(it) }
                val collections = collectionsRepository.topLevel()
                    .filter { TextNormalizer.normalize(it.displayName).contains(TextNormalizer.normalize(term)) }
                    .map { ListItem.CollectionItem(Collection(it.id, it.displayName)) }
                val items = games + apps + collections
                _state.value = State(
                    breadcrumb = resources.getString(R.string.global_search_title, term),
                    items = items,
                    allItems = items,
                    favoriteRomIds = collectionsRepository.favoriteRomIds(),
                    favoriteAppIds = collectionsRepository.favoriteAppIds(),
                    selectedIndex = 0,
                    isLoading = false,
                    isGlobalSearch = true,
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun moveSelectedToTop() {
        val current = _state.value
        val idx = current.selectedIndex
        if (idx <= 0) return
        val items = current.items.toMutableList()
        val item = items.removeAt(idx)
        items.add(0, item)
        _state.value = current.copy(items = items, selectedIndex = 0, scrollTarget = 0)
        firstVisibleIndex = 0
    }

    fun reload(onReady: () -> Unit = {}) {
        val current = _state.value
        val preserveIndex = current.selectedIndex
        val preserveScroll = firstVisibleIndex
        val prevCount = current.items.size
        val preserveId = stableIdOf(current.items.getOrNull(preserveIndex))
        if (current.isCollectionsList) {
            collectionsListSaved = SavedPos(preserveId, preserveIndex, preserveScroll)
            collectionsListItemCount = prevCount
            loadCollectionsList(restoreIndex = true, onReady = onReady)
        } else if (current.isCollection && current.collectionId != null) {
            scope.launch(Dispatchers.IO) {
                loadCollectionByIdInternal(current.collectionId) {
                    val s = _state.value
                    val (idx, scroll) = reloadPosition(s.items, preserveId, preserveIndex, preserveScroll, prevCount)
                    _state.value = s.copy(selectedIndex = idx, scrollTarget = scroll)
                    onReady()
                }
            }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            loadApkList(current.platformTag, current.breadcrumb) {
                val s = _state.value
                val (idx, scroll) = reloadPosition(s.items, preserveId, preserveIndex, preserveScroll, prevCount = -1)
                _state.value = s.copy(selectedIndex = idx, scrollTarget = scroll)
                onReady()
            }
        } else if (current.platformTag == "recently_played") {
            _state.value = current.copy(items = emptyList(), isLoading = true)
            loadRecentlyPlayed(onReady)
        } else if (current.platformTags.isNotEmpty()) {
            loadGames(current.platformTag, current.platformTags, current.subfolderPath, preserveIndex, preserveScroll, prevCount, preserveId, onReady)
        } else {
            onReady()
        }
    }

    fun enterSubfolder(folderName: String) {
        val current = _state.value
        indexStack.add(current.selectedIndex to firstVisibleIndex)
        breadcrumbStack.add(folderName)
        val subPath = breadcrumbStack.joinToString(File.separator)
        loadGames(current.platformTag, current.platformTags, subPath)
    }

    fun exitSubfolder(): Boolean {
        if (breadcrumbStack.isEmpty()) return false
        breadcrumbStack.removeAt(breadcrumbStack.lastIndex)
        val (parentIndex, parentScroll) = if (indexStack.isNotEmpty()) indexStack.removeAt(indexStack.lastIndex) else (0 to 0)
        val subPath = if (breadcrumbStack.isEmpty()) null else breadcrumbStack.joinToString(File.separator)
        loadGames(_state.value.platformTag, _state.value.platformTags, subPath, parentIndex, parentScroll)
        return true
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.items.isEmpty()) return@update current
            val size = current.items.size
            val raw = current.selectedIndex + delta
            val newIndex = ((raw % size) + size) % size
            current.copy(selectedIndex = newIndex)
        }
    }

    fun setSelectedIndex(index: Int) {
        _state.update { it.copy(selectedIndex = index) }
    }

    fun getSelectedItem(): ListItem? {
        val current = _state.value
        return current.items.getOrNull(current.selectedIndex)
    }

    fun toggleFavorite(onDone: () -> Unit = {}) {
        val current = _state.value
        val item = current.items.getOrNull(current.selectedIndex) ?: return
        if (item is ListItem.SubfolderItem || item is ListItem.ChildCollectionItem) return
        if (current.isCollectionsList) return
        val ref = itemRef(item) ?: return
        val oldIndex = current.selectedIndex
        scope.launch(Dispatchers.IO) {
            val favId = collectionsRepository.favoritesId() ?: return@launch
            val isFav = collectionsRepository.isMember(favId, ref) ||
                (current.isCollection && current.isFavorites)
            if (isFav) collectionsRepository.removeMember(favId, ref)
            else collectionsRepository.addMember(favId, ref)
            val newItems = if (current.isCollection && current.collectionId != null) {
                val romIds = collectionsRepository.romIdsIn(current.collectionId)
                val appIds = collectionsRepository.appIdsIn(current.collectionId)
                val children = collectionsRepository.children(current.collectionId).map {
                    ListItem.ChildCollectionItem(Collection(it.id, it.displayName))
                }
                val roms = romIds.mapNotNull { romsRepository.gameById(it) }.map { ListItem.RomItem(it) }
                val apps = appIds.mapNotNull { appsRepository.byId(it) }.map { ListItem.AppItem(it) }
                val combined = children + roms + apps
                if (current.isFavorites) combined
                else sortFavoritesFirst(combined)
            } else if (current.platformTag == "tools" || current.platformTag == "ports") {
                val type = if (current.platformTag == "tools") AppType.TOOL else AppType.PORT
                appsRepository.all(type).map { ListItem.AppItem(it) }
            } else {
                loadPlatformItems(current.platformTag, current.platformTags, current.subfolderPath)
            }
            val sortedItems = if (current.platformTag == "tools" || current.platformTag == "ports") {
                val freshFavs = collectionsRepository.favoriteAppIds()
                newItems.sortedBy { (it as? ListItem.AppItem)?.app?.id !in freshFavs }
            } else newItems
            val newIndex = sortedItems.indexOfFirst { itemRef(it) == ref }
                .let { if (it >= 0) it else oldIndex.coerceAtMost(sortedItems.lastIndex.coerceAtLeast(0)) }
            _state.value = current.copy(
                items = sortedItems,
                allItems = sortedItems,
                searchTerm = null,
                favoriteRomIds = collectionsRepository.favoriteRomIds(),
                favoriteAppIds = collectionsRepository.favoriteAppIds(),
                selectedIndex = newIndex,
                scrollTarget = -1,
            )
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun setSearch(term: String) {
        _state.update {
            if (it.reorderMode || it.multiSelectMode) return@update it
            val t = term.trim()
            it.copy(searchTerm = t.ifBlank { null }, items = applyItemFilter(it.allItems, t), selectedIndex = 0, scrollTarget = 0)
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchTerm = null, items = it.allItems, selectedIndex = 0, scrollTarget = 0) }
    }

    fun isSearching(): Boolean = !_state.value.searchTerm.isNullOrBlank()

    fun enterMultiSelect() {
        _state.update { current ->
            if (current.reorderMode || current.multiSelectMode) return@update current
            val base = if (current.searchTerm != null) current.copy(searchTerm = null, items = current.allItems) else current
            val item = base.items.getOrNull(base.selectedIndex)
            val initial = if (item != null && item !is ListItem.SubfolderItem && item !is ListItem.ChildCollectionItem)
                setOf(base.selectedIndex) else emptySet()
            base.copy(multiSelectMode = true, checkedIndices = initial)
        }
    }

    fun isMultiSelectMode(): Boolean = _state.value.multiSelectMode

    fun toggleChecked() {
        _state.update { current ->
            if (!current.multiSelectMode) return@update current
            val idx = current.selectedIndex
            val item = current.items.getOrNull(idx) ?: return@update current
            if (item is ListItem.SubfolderItem || item is ListItem.ChildCollectionItem) return@update current
            val newChecked = if (idx in current.checkedIndices) current.checkedIndices - idx else current.checkedIndices + idx
            current.copy(checkedIndices = newChecked)
        }
    }

    fun confirmMultiSelect(): Set<Int> {
        val prev = _state.value
        _state.update { it.copy(multiSelectMode = false, checkedIndices = emptySet()) }
        pendingRescan?.let { pending ->
            pendingRescan = null
            handleRescanResult(pending)
        }
        return prev.checkedIndices
    }

    fun cancelMultiSelect() {
        _state.update { it.copy(multiSelectMode = false, checkedIndices = emptySet()) }
        pendingRescan?.let { pending ->
            pendingRescan = null
            handleRescanResult(pending)
        }
    }

    fun hasChildCollections(): Boolean = _state.value.items.any { it is ListItem.ChildCollectionItem }

    fun enterReorderMode() {
        _state.update { current ->
            val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
            val canReorder = current.isCollectionsList || isApkList || current.isCollection
            if (!canReorder || current.items.isEmpty()) return@update current
            val base = if (current.searchTerm != null) current.copy(searchTerm = null, items = current.allItems) else current
            base.copy(reorderMode = true, reorderOriginalIndex = base.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx <= 0) return@update current
            if (!canReorderSwap(current, idx, idx - 1)) return@update current
            swapAt(current, idx, idx - 1)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx >= current.items.lastIndex) return@update current
            if (!canReorderSwap(current, idx, idx + 1)) return@update current
            swapAt(current, idx, idx + 1)
        }
    }

    private fun canReorderSwap(current: State, a: Int, b: Int): Boolean {
        val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
        if (isApkList) return true
        val itemA = current.items[a]; val itemB = current.items[b]
        if ((itemA is ListItem.ChildCollectionItem) != (itemB is ListItem.ChildCollectionItem)) return false
        val favA = isItemFavorited(current, itemA)
        val favB = isItemFavorited(current, itemB)
        if (favA != favB) return false
        return true
    }

    private fun isItemFavorited(current: State, item: ListItem): Boolean = when (item) {
        is ListItem.RomItem -> item.rom.id in current.favoriteRomIds
        is ListItem.AppItem -> item.app.id in current.favoriteAppIds
        else -> false
    }

    private fun swapAt(current: State, a: Int, b: Int): State {
        val items = current.items.toMutableList()
        val ti = items[a]; items[a] = items[b]; items[b] = ti
        return current.copy(items = items, selectedIndex = b)
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        if (current.isCollectionsList) {
            val ids = current.items.mapNotNull { (it as? ListItem.CollectionItem)?.collection?.id }
            scope.launch(Dispatchers.IO) { collectionsRepository.setCollectionOrder(ids) }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            val ids = current.items.mapNotNull { (it as? ListItem.AppItem)?.app?.id }
            scope.launch(Dispatchers.IO) { appsRepository.setOrder(ids) }
        } else if (current.isCollection && current.collectionId != null) {
            val collectionId = current.collectionId
            val childIds = current.items.mapNotNull { (it as? ListItem.ChildCollectionItem)?.collection?.id }
            val memberRefs = current.items.mapNotNull { itemRef(it) }
            scope.launch(Dispatchers.IO) {
                collectionsRepository.setCollectionOrder(childIds)
                collectionsRepository.setMemberOrder(collectionId, memberRefs)
            }
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
        pendingRescan?.let { pending ->
            pendingRescan = null
            handleRescanResult(pending)
        }
    }

    fun cancelReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        if (current.isCollectionsList) {
            loadCollectionsList()
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            loadApkList(current.platformTag, current.breadcrumb)
        } else if (current.isCollection && current.collectionId != null) {
            val id = current.collectionId
            scope.launch(Dispatchers.IO) { loadCollectionByIdInternal(id) {} }
        }
        pendingRescan?.let { pending ->
            pendingRescan = null
            handleRescanResult(pending)
        }
    }

    private fun loadGames(
        tag: String,
        tags: List<String>,
        subfolder: String?,
        preserveIndex: Int = 0,
        preserveScroll: Int = 0,
        prevCount: Int = -1,
        preserveId: String? = null,
        onReady: () -> Unit = {},
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val items = loadPlatformItems(tag, tags, subfolder)
                val displayName = platformConfig.getDisplayName(tag)
                val breadcrumb = if (breadcrumbStack.isEmpty()) displayName
                else "/${breadcrumbStack.last()}"
                val (idx, scroll) = reloadPosition(items, preserveId, preserveIndex, preserveScroll, prevCount)
                _state.value = State(
                    platformTag = tag,
                    platformTags = tags,
                    breadcrumb = breadcrumb,
                    items = items,
                    allItems = items,
                    favoriteRomIds = collectionsRepository.favoriteRomIds(),
                    selectedIndex = idx,
                    scrollTarget = scroll,
                    subfolderPath = subfolder,
                    isLoading = false
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    private fun loadPlatformItems(tag: String, tags: List<String>, subfolder: String?): List<ListItem> {
        val items = tags.flatMap { romsRepository.gamesForPlatform(it.uppercase(), subfolder) }
        return sortFavoritesFirst(items)
    }

    private fun sortFavoritesFirst(items: List<ListItem>): List<ListItem> {
        val favRoms = collectionsRepository.favoriteRomIds()
        val favApps = collectionsRepository.favoriteAppIds()
        val (subfolders, others) = items.partition { it is ListItem.SubfolderItem || it is ListItem.ChildCollectionItem }
        val (favs, rest) = others.partition { item ->
            when (item) {
                is ListItem.RomItem -> item.rom.id in favRoms
                is ListItem.AppItem -> item.app.id in favApps
                else -> false
            }
        }
        return subfolders + favs + rest
    }

    private fun itemRef(item: ListItem): LibraryRef? = when (item) {
        is ListItem.RomItem -> LibraryRef.Rom(item.rom.id)
        is ListItem.AppItem -> LibraryRef.App(item.app.id)
        else -> null
    }

    fun close() { scope.cancel() }
}
