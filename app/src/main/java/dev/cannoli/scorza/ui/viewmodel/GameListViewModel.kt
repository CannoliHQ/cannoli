package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.R
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.config.PlatformConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameListViewModel(
    private val romsRepository: RomsRepository,
    private val romScanner: RomScanner,
    private val appsRepository: AppsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val platformConfig: PlatformConfig,
    private val resources: android.content.res.Resources,
    private val isArcadeTag: (String) -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var showFavoriteStars: Boolean = true

    data class State(
        val platformTag: String = "",
        val platformTags: List<String> = emptyList(),
        val breadcrumb: String = "",
        val items: List<ListItem> = emptyList(),
        val favoriteRomIds: Set<Long> = emptySet(),
        val favoriteAppIds: Set<Long> = emptySet(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val subfolderPath: String? = null,
        val isLoading: Boolean = true,
        val isCollection: Boolean = false,
        val collectionName: String? = null,
        val collectionId: Long? = null,
        val isCollectionsList: Boolean = false,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        val multiSelectMode: Boolean = false,
        val checkedIndices: Set<Int> = emptySet()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0

    private val breadcrumbStack = mutableListOf<String>()
    private val indexStack = mutableListOf<Pair<Int, Int>>()
    private var collectionsListSaved: Pair<Int, Int> = 0 to 0
    private var collectionsListItemCount: Int = 0
    private val collectionStack = mutableListOf<Triple<Long, Int, Int>>()

    fun saveCollectionsPosition() {
        val current = _state.value
        if (current.isCollectionsList) {
            collectionsListSaved = current.selectedIndex to firstVisibleIndex
            collectionsListItemCount = current.items.size
        }
    }

    fun loadPlatform(tag: String, tags: List<String> = listOf(tag), onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val items = scanAndLoadPlatform(tag, tags, null)
                val displayName = platformConfig.getDisplayName(tag)
                val favRoms = collectionsRepository.favoriteRomIds()
                _state.value = State(
                    platformTag = tag,
                    platformTags = tags,
                    breadcrumb = displayName,
                    items = items,
                    favoriteRomIds = favRoms,
                    selectedIndex = 0,
                    isLoading = false
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun loadCollection(collectionName: String, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollectionsList) {
            collectionsListSaved = current.selectedIndex to firstVisibleIndex
            collectionsListItemCount = current.items.size
        }
        breadcrumbStack.clear()
        indexStack.clear()
        collectionStack.clear()
        scope.launch(Dispatchers.IO) {
            val id = resolveCollectionByName(collectionName)
            if (id == null) {
                _state.value = State(
                    breadcrumb = collectionName,
                    items = emptyList(),
                    isLoading = false,
                    isCollection = true,
                    collectionName = collectionName,
                    collectionId = null,
                )
                withContext(Dispatchers.Main) { onReady() }
                return@launch
            }
            loadCollectionByIdInternal(id, onReady)
        }
    }

    fun enterChildCollection(childStem: String, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollection && current.collectionId != null) {
            collectionStack.add(Triple(current.collectionId, current.selectedIndex, firstVisibleIndex))
        }
        scope.launch(Dispatchers.IO) {
            val id = resolveCollectionByName(childStem) ?: return@launch
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
        val childItems = children.map { ListItem.ChildCollectionItem(childCollection(it.id, it.displayName)) }
        val romIds = collectionsRepository.romIdsIn(collectionId)
        val appIds = collectionsRepository.appIdsIn(collectionId)
        val romItems = romIds.mapNotNull { romsRepository.gameById(it) }.map { ListItem.RomItem(it) }
        val appItems = appIds.mapNotNull { appsRepository.byId(it) }.map { ListItem.AppItem(it) }
        val items = childItems + romItems + appItems
        val parent = row.parentId?.let { collectionsRepository.byId(it) }
        val breadcrumb = if (parent != null) "/${row.displayName}" else row.displayName
        _state.value = State(
            breadcrumb = breadcrumb,
            items = items,
            favoriteRomIds = collectionsRepository.favoriteRomIds(),
            favoriteAppIds = collectionsRepository.favoriteAppIds(),
            selectedIndex = 0,
            isLoading = false,
            isCollection = true,
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
                val entries = recentlyPlayedRepository.recent(limit = 50)
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
                ListItem.CollectionItem(topLevelCollection(row.id, row.displayName))
            }
            val (idx, scroll) = if (restoreIndex && collectionsListItemCount > 0 && items.isNotEmpty()) {
                val maxIdx = items.lastIndex.coerceAtLeast(0)
                collectionsListSaved.first.coerceAtMost(maxIdx) to collectionsListSaved.second.coerceAtMost(maxIdx)
            } else 0 to 0
            _state.value = State(
                breadcrumb = resources.getString(R.string.label_collections),
                items = items,
                selectedIndex = idx,
                scrollTarget = scroll,
                isLoading = false,
                isCollectionsList = true
            )
            withContext(Dispatchers.Main) { onReady() }
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
        if (current.isCollectionsList) {
            collectionsListSaved = preserveIndex to preserveScroll
            collectionsListItemCount = prevCount
            loadCollectionsList(restoreIndex = true, onReady = onReady)
        } else if (current.isCollection && current.collectionId != null) {
            scope.launch(Dispatchers.IO) {
                loadCollectionByIdInternal(current.collectionId) {
                    val s = _state.value
                    if (s.items.size == prevCount && prevCount > 0) {
                        _state.value = s.copy(
                            selectedIndex = preserveIndex.coerceAtMost(s.items.lastIndex.coerceAtLeast(0)),
                            scrollTarget = preserveScroll.coerceAtMost(s.items.lastIndex.coerceAtLeast(0))
                        )
                    } else {
                        _state.value = s.copy(selectedIndex = 0, scrollTarget = 0)
                    }
                    onReady()
                }
            }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            loadApkList(current.platformTag, current.breadcrumb) {
                val s = _state.value
                _state.value = s.copy(
                    selectedIndex = preserveIndex.coerceAtMost(s.items.lastIndex.coerceAtLeast(0)),
                    scrollTarget = preserveScroll.coerceAtMost(s.items.lastIndex.coerceAtLeast(0))
                )
                onReady()
            }
        } else if (current.platformTag == "recently_played") {
            _state.value = current.copy(items = emptyList(), isLoading = true)
            loadRecentlyPlayed(onReady)
        } else if (current.platformTags.isNotEmpty()) {
            loadGames(current.platformTag, current.platformTags, current.subfolderPath, preserveIndex, preserveScroll, prevCount, onReady)
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

    fun jumpToIndex(index: Int, scrollTarget: Int) {
        _state.update { it.copy(selectedIndex = index, scrollTarget = scrollTarget) }
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
                (current.isCollection && current.collectionName == "Favorites")
            if (isFav) collectionsRepository.removeMember(favId, ref)
            else collectionsRepository.addMember(favId, ref)
            val newItems = if (current.isCollection && current.collectionId != null) {
                val romIds = collectionsRepository.romIdsIn(current.collectionId)
                val appIds = collectionsRepository.appIdsIn(current.collectionId)
                val children = collectionsRepository.children(current.collectionId).map {
                    ListItem.ChildCollectionItem(childCollection(it.id, it.displayName))
                }
                val roms = romIds.mapNotNull { romsRepository.gameById(it) }.map { ListItem.RomItem(it) }
                val apps = appIds.mapNotNull { appsRepository.byId(it) }.map { ListItem.AppItem(it) }
                children + roms + apps
            } else if (current.platformTag == "tools" || current.platformTag == "ports") {
                val type = if (current.platformTag == "tools") AppType.TOOL else AppType.PORT
                appsRepository.all(type).map { ListItem.AppItem(it) }
            } else {
                scanAndLoadPlatform(current.platformTag, current.platformTags, current.subfolderPath)
            }
            val sortedItems = if (current.platformTag == "tools" || current.platformTag == "ports") {
                val freshFavs = collectionsRepository.favoriteAppIds()
                newItems.sortedBy { (it as? ListItem.AppItem)?.app?.id !in freshFavs }
            } else newItems
            val newIndex = sortedItems.indexOfFirst { itemRef(it) == ref }
                .let { if (it >= 0) it else oldIndex.coerceAtMost(sortedItems.lastIndex.coerceAtLeast(0)) }
            _state.value = current.copy(
                items = sortedItems,
                favoriteRomIds = collectionsRepository.favoriteRomIds(),
                favoriteAppIds = collectionsRepository.favoriteAppIds(),
                selectedIndex = newIndex,
                scrollTarget = -1,
            )
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun enterMultiSelect() {
        _state.update { current ->
            if (current.reorderMode || current.multiSelectMode) return@update current
            val item = current.items.getOrNull(current.selectedIndex)
            val initial = if (item != null && item !is ListItem.SubfolderItem && item !is ListItem.ChildCollectionItem)
                setOf(current.selectedIndex) else emptySet()
            current.copy(multiSelectMode = true, checkedIndices = initial)
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
        return prev.checkedIndices
    }

    fun cancelMultiSelect() {
        _state.update { it.copy(multiSelectMode = false, checkedIndices = emptySet()) }
    }

    fun hasChildCollections(): Boolean = _state.value.items.any { it is ListItem.ChildCollectionItem }

    fun enterReorderMode() {
        _state.update { current ->
            val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
            val canReorder = current.isCollectionsList || isApkList || current.isCollection
            if (!canReorder || current.items.isEmpty()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
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
            val ids = current.items.mapNotNull { (it as? ListItem.CollectionItem)?.collection?.let(::collectionStemToId) }
            scope.launch(Dispatchers.IO) { collectionsRepository.setCollectionOrder(ids) }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            val type = if (current.platformTag == "tools") AppType.TOOL else AppType.PORT
            val ids = current.items.mapNotNull { (it as? ListItem.AppItem)?.app?.id }
            scope.launch(Dispatchers.IO) { appsRepository.setOrder(type, ids) }
        } else if (current.isCollection && current.collectionId != null) {
            val collectionId = current.collectionId
            val childIds = current.items.mapNotNull { (it as? ListItem.ChildCollectionItem)?.collection?.let(::collectionStemToId) }
            val memberRefs = current.items.mapNotNull { itemRef(it) }
            scope.launch(Dispatchers.IO) {
                collectionsRepository.setCollectionOrder(childIds)
                collectionsRepository.setMemberOrder(collectionId, memberRefs)
            }
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
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
    }

    private fun loadGames(
        tag: String,
        tags: List<String>,
        subfolder: String?,
        preserveIndex: Int = 0,
        preserveScroll: Int = 0,
        prevCount: Int = -1,
        onReady: () -> Unit = {},
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val items = scanAndLoadPlatform(tag, tags, subfolder)
                val displayName = platformConfig.getDisplayName(tag)
                val breadcrumb = if (breadcrumbStack.isEmpty()) displayName
                else (listOf(displayName) + breadcrumbStack).joinToString(" › ")
                val sameSize = prevCount >= 0 && items.size == prevCount && prevCount > 0
                val maxIdx = items.lastIndex.coerceAtLeast(0)
                val (idx, scroll) = if (sameSize || prevCount < 0) {
                    preserveIndex.coerceAtMost(maxIdx) to preserveScroll.coerceAtMost(maxIdx)
                } else 0 to 0
                _state.value = State(
                    platformTag = tag,
                    platformTags = tags,
                    breadcrumb = breadcrumb,
                    items = items,
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

    private fun scanAndLoadPlatform(tag: String, tags: List<String>, subfolder: String?): List<ListItem> {
        for (t in tags) romScanner.scanPlatform(t.uppercase(), isArcade = isArcadeTag(t))
        return tags.flatMap { romsRepository.gamesForPlatform(it.uppercase(), subfolder) }
    }

    private fun resolveCollectionByName(name: String): Long? {
        if (name.equals("Favorites", ignoreCase = true)) return collectionsRepository.favoritesId()
        return collectionsRepository.all().firstOrNull { it.displayName.equals(name, ignoreCase = true) }?.id
    }

    private fun collectionStemToId(coll: Collection): Long? {
        return collectionsRepository.all().firstOrNull { it.displayName == coll.displayName }?.id
    }

    private fun topLevelCollection(id: Long, displayName: String): Collection {
        return Collection(stem = displayName, file = File(displayName))
    }

    private fun childCollection(id: Long, displayName: String): Collection {
        return Collection(stem = displayName, file = File(displayName))
    }

    private fun itemRef(item: ListItem): LibraryRef? = when (item) {
        is ListItem.RomItem -> LibraryRef.Rom(item.rom.id)
        is ListItem.AppItem -> LibraryRef.App(item.app.id)
        else -> null
    }

    fun close() { scope.cancel() }
}
