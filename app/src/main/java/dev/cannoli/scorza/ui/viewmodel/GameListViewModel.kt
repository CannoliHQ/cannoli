package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.R
import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.scanner.CollectionManager
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.OrderingManager
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.scanner.RecentlyPlayedManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameListViewModel(
    private val scanner: FileScanner,
    private val collectionManager: CollectionManager,
    private val orderingManager: OrderingManager,
    private val recentlyPlayedManager: RecentlyPlayedManager,
    private val platformResolver: PlatformResolver,
    private val resources: android.content.res.Resources
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var showFavoriteStars: Boolean = true

    data class State(
        val platformTag: String = "",
        val platformTags: List<String> = emptyList(),
        val breadcrumb: String = "",
        val games: List<Game> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val subfolderPath: String? = null,
        val isLoading: Boolean = true,
        val isCollection: Boolean = false,
        val collectionName: String? = null,
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
    private val indexStack = mutableListOf<Pair<Int, Int>>() // selectedIndex to firstVisibleIndex
    private var collectionsListSaved: Pair<Int, Int> = 0 to 0
    private var collectionsListItemCount: Int = 0
    private val collectionStack = mutableListOf<Triple<String, Int, Int>>() // name, selectedIndex, firstVisibleIndex

    fun saveCollectionsPosition() {
        val current = _state.value
        if (current.isCollectionsList) {
            collectionsListSaved = current.selectedIndex to firstVisibleIndex
            collectionsListItemCount = current.games.size
        }
    }

    fun loadPlatform(tag: String, tags: List<String> = listOf(tag), onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val games = scanner.scanGames(tags, null)
                val displayName = platformResolver.getDisplayName(tag)
                _state.value = State(
                    platformTag = tag,
                    platformTags = tags,
                    breadcrumb = displayName,
                    games = games,
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
            collectionsListItemCount = current.games.size
        }
        breadcrumbStack.clear()
        indexStack.clear()
        collectionStack.clear()
        loadCollectionInternal(collectionName, onReady)
    }

    fun enterChildCollection(childStem: String, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollection && current.collectionName != null) {
            collectionStack.add(Triple(current.collectionName, current.selectedIndex, firstVisibleIndex))
        }
        loadCollectionInternal(childStem, onReady)
    }

    fun exitChildCollection(onReady: () -> Unit = {}): Boolean {
        if (collectionStack.isEmpty()) return false
        val (parentName, parentIndex, parentScroll) = collectionStack.removeAt(collectionStack.lastIndex)
        loadCollectionInternal(parentName) {
            _state.update { it.copy(selectedIndex = parentIndex, scrollTarget = parentScroll) }
            onReady()
        }
        return true
    }

    private fun loadCollectionInternal(collectionStem: String, onReady: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                val games = scanner.scanCollectionGames(collectionStem)
                val childStems = collectionManager.getChildCollections(collectionStem)
                val childItems = childStems.map { childStem ->
                    Game(
                        file = java.io.File(childStem),
                        displayName = "/" + Collection.childDisplayName(childStem, collectionStem),
                        platformTag = "",
                        isChildCollection = true
                    )
                }
                _state.value = State(
                    breadcrumb = collectionManager.getCollectionParent(collectionStem)?.let {
                        Collection.childDisplayName(collectionStem, it)
                    } ?: Collection.stemToDisplayName(collectionStem),
                    games = childItems + games,
                    selectedIndex = 0,
                    isLoading = false,
                    isCollection = true,
                    collectionName = collectionStem
                )
            } finally {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    fun loadApkList(type: String, displayName: String, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        scope.launch(Dispatchers.IO) {
            try {
                val entries = if (type == "tools") scanner.scanTools() else scanner.scanPorts()
                val games = entries.map { (file, name, launch) ->
                    Game(
                        file = file,
                        displayName = name,
                        platformTag = type,
                        launchTarget = launch
                    )
                }
                val starred = if (showFavoriteStars) {
                    val favPaths = collectionManager.getFavoritePaths()
                    games.map { game ->
                        if (game.file.absolutePath in favPaths) game.copy(displayName = "★ ${game.displayName}") else game
                    }
                } else games
                val order = if (type == "tools") orderingManager.loadToolOrder() else orderingManager.loadPortOrder()
                val ordered = applyCustomOrder(starred, order)
                    .sortedBy { !it.displayName.startsWith("★") }
                _state.value = State(
                    platformTag = type,
                    breadcrumb = displayName,
                    games = ordered,
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
                val paths = recentlyPlayedManager.load()
                val toolPaths = paths.filter { scanner.resolveGameFromPath(it)?.platformTag == "tools" }
                toolPaths.forEach { recentlyPlayedManager.remove(it) }
                val games = (paths - toolPaths.toSet()).mapNotNull { scanner.resolveGameFromPath(it) }

                val nameCount = games.groupBy { it.displayName }
                val disambiguated = games.map { game ->
                    if ((nameCount[game.displayName]?.size ?: 0) > 1 && game.platformTag.isNotEmpty()) {
                        val platName = platformResolver.getDisplayName(game.platformTag)
                        game.copy(displayName = "${game.displayName} ($platName)")
                    } else game
                }

                _state.value = State(
                    platformTag = "recently_played",
                    breadcrumb = resources.getString(R.string.label_recently_played),
                    games = disambiguated,
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
            val collections = collectionManager.scanCollections()
                .filter { !it.stem.equals("Favorites", ignoreCase = true) && collectionManager.isTopLevelCollection(it.stem) }
            val order = orderingManager.loadCollectionOrder()
            val ordered = if (order.isEmpty()) collections else {
                val byStem = collections.associateBy { it.stem }
                val result = mutableListOf<Collection>()
                for (stem in order) {
                    byStem[stem]?.let { result.add(it) }
                }
                result.addAll(collections.filter { it.stem !in order })
                result
            }
            val games = ordered.map { coll ->
                Game(
                    file = coll.file,
                    displayName = coll.displayName,
                    platformTag = ""
                )
            }
            val (idx, scroll) = if (restoreIndex && collectionsListItemCount > 0 && games.isNotEmpty()) {
                val maxIdx = games.lastIndex.coerceAtLeast(0)
                collectionsListSaved.first.coerceAtMost(maxIdx) to collectionsListSaved.second.coerceAtMost(maxIdx)
            } else {
                0 to 0
            }
            _state.value = State(
                breadcrumb = resources.getString(R.string.label_collections),
                games = games,
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
        val games = current.games.toMutableList()
        val game = games.removeAt(idx)
        games.add(0, game)
        _state.value = current.copy(games = games, selectedIndex = 0, scrollTarget = 0)
        firstVisibleIndex = 0
    }

    fun reload() {
        val current = _state.value
        val preserveIndex = current.selectedIndex
        val preserveScroll = firstVisibleIndex
        val prevCount = current.games.size
        if (current.isCollectionsList) {
            collectionsListSaved = preserveIndex to preserveScroll
            collectionsListItemCount = prevCount
            loadCollectionsList(restoreIndex = true)
        } else if (current.isCollection && current.collectionName != null) {
            loadCollectionInternal(current.collectionName) {
                val s = _state.value
                if (s.games.size == prevCount && prevCount > 0) {
                    _state.value = s.copy(
                        selectedIndex = preserveIndex.coerceAtMost(s.games.lastIndex.coerceAtLeast(0)),
                        scrollTarget = preserveScroll.coerceAtMost(s.games.lastIndex.coerceAtLeast(0))
                    )
                } else {
                    _state.value = s.copy(selectedIndex = 0, scrollTarget = 0)
                }
            }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            loadApkList(current.platformTag, current.breadcrumb) {
                val s = _state.value
                _state.value = s.copy(
                    selectedIndex = preserveIndex.coerceAtMost(s.games.lastIndex.coerceAtLeast(0)),
                    scrollTarget = preserveScroll.coerceAtMost(s.games.lastIndex.coerceAtLeast(0))
                )
            }
        } else if (current.platformTag == "recently_played") {
            _state.value = current.copy(games = emptyList(), isLoading = true)
            loadRecentlyPlayed()
        } else if (current.platformTags.isNotEmpty()) {
            loadGames(current.platformTags, current.subfolderPath, preserveIndex, preserveScroll, prevCount)
        }
    }

    fun enterSubfolder(folderName: String) {
        val current = _state.value
        indexStack.add(current.selectedIndex to firstVisibleIndex)
        breadcrumbStack.add(folderName)
        val subPath = breadcrumbStack.joinToString("/")
        loadGames(current.platformTags, subPath)
    }

    fun exitSubfolder(): Boolean {
        if (breadcrumbStack.isEmpty()) return false
        breadcrumbStack.removeAt(breadcrumbStack.lastIndex)
        val (parentIndex, parentScroll) = if (indexStack.isNotEmpty()) indexStack.removeAt(indexStack.lastIndex) else (0 to 0)
        val subPath = if (breadcrumbStack.isEmpty()) null else breadcrumbStack.joinToString("/")
        loadGames(_state.value.platformTags, subPath, parentIndex, parentScroll)
        return true
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.games.isEmpty()) return@update current
            val size = current.games.size
            val raw = current.selectedIndex + delta
            val newIndex = ((raw % size) + size) % size
            current.copy(selectedIndex = newIndex)
        }
    }

    fun jumpToIndex(index: Int, scrollTarget: Int) {
        _state.update { it.copy(selectedIndex = index, scrollTarget = scrollTarget) }
    }

    fun getSelectedGame(): Game? {
        val current = _state.value
        return current.games.getOrNull(current.selectedIndex)
    }

    fun toggleFavorite(onDone: () -> Unit = {}) {
        val current = _state.value
        val game = current.games.getOrNull(current.selectedIndex) ?: return
        if (game.isSubfolder || game.isChildCollection || current.isCollectionsList) return
        val path = game.file.absolutePath
        val isFav = game.displayName.startsWith("★") ||
            (current.isCollection && current.collectionName == "Favorites")
        val oldIndex = current.selectedIndex
        scope.launch(Dispatchers.IO) {
            if (isFav) collectionManager.removeFromCollection("Favorites", path)
            else collectionManager.addToCollection("Favorites", path)
            val newGames = if (current.isCollection && current.collectionName != null) {
                scanner.scanCollectionGames(current.collectionName)
            } else if (current.platformTag == "tools" || current.platformTag == "ports") {
                val entries = if (current.platformTag == "tools") scanner.scanTools() else scanner.scanPorts()
                val favPaths = if (showFavoriteStars) collectionManager.getFavoritePaths() else emptySet()
                val games = entries.map { (file, name, launch) ->
                    val label = if (showFavoriteStars && file.absolutePath in favPaths) "★ $name" else name
                    Game(file = file, displayName = label, platformTag = current.platformTag, launchTarget = launch)
                }
                val order = if (current.platformTag == "tools") orderingManager.loadToolOrder() else orderingManager.loadPortOrder()
                applyCustomOrder(games, order).sortedBy { !it.displayName.startsWith("★") }
            } else {
                scanner.scanGames(current.platformTags, current.subfolderPath)
            }
            val newIndex = newGames.indexOfFirst { it.file.absolutePath == path }
                .let { if (it >= 0) it else oldIndex.coerceAtMost(newGames.lastIndex.coerceAtLeast(0)) }
            _state.value = current.copy(games = newGames, selectedIndex = newIndex, scrollTarget = -1)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun enterMultiSelect() {
        _state.update { current ->
            if (current.reorderMode || current.multiSelectMode) return@update current
            val game = current.games.getOrNull(current.selectedIndex)
            val initial = if (game != null && !game.isSubfolder && !game.isChildCollection) setOf(current.selectedIndex) else emptySet()
            current.copy(multiSelectMode = true, checkedIndices = initial)
        }
    }

    fun isMultiSelectMode(): Boolean = _state.value.multiSelectMode

    fun toggleChecked() {
        _state.update { current ->
            if (!current.multiSelectMode) return@update current
            val idx = current.selectedIndex
            val game = current.games.getOrNull(idx) ?: return@update current
            if (game.isSubfolder || game.isChildCollection) return@update current
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

    fun hasChildCollections(): Boolean = _state.value.games.any { it.isChildCollection }

    fun enterReorderMode() {
        _state.update { current ->
            val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
            val canReorder = current.isCollectionsList || isApkList || current.isCollection
            if (!canReorder || current.games.isEmpty()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx <= 0) return@update current
            val item = current.games[idx]
            val target = current.games[idx - 1]
            val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
            if (!isApkList) {
                if (item.isChildCollection != target.isChildCollection) return@update current
                if (item.displayName.startsWith("★") != target.displayName.startsWith("★")) return@update current
            }
            val games = current.games.toMutableList()
            games[idx] = target; games[idx - 1] = item
            current.copy(games = games, selectedIndex = idx - 1)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx >= current.games.lastIndex) return@update current
            val item = current.games[idx]
            val target = current.games[idx + 1]
            val isApkList = current.platformTag == "tools" || current.platformTag == "ports"
            if (!isApkList) {
                if (item.isChildCollection != target.isChildCollection) return@update current
                if (item.displayName.startsWith("★") != target.displayName.startsWith("★")) return@update current
            }
            val games = current.games.toMutableList()
            games[idx] = target; games[idx + 1] = item
            current.copy(games = games, selectedIndex = idx + 1)
        }
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        if (current.isCollectionsList) {
            val stems = current.games.map { it.file.nameWithoutExtension }
            scope.launch(Dispatchers.IO) { orderingManager.saveCollectionOrder(stems) }
        } else if (current.platformTag == "tools") {
            val names = current.games.map { it.displayName }
            scope.launch(Dispatchers.IO) { orderingManager.saveToolOrder(names) }
        } else if (current.platformTag == "ports") {
            val names = current.games.map { it.displayName }
            scope.launch(Dispatchers.IO) { orderingManager.savePortOrder(names) }
        } else if (current.isCollection && current.collectionName != null) {
            val childStems = current.games.filter { it.isChildCollection }.map { it.file.name }
            val gamePaths = current.games.filter { !it.isChildCollection }.map { it.file.absolutePath }
            scope.launch(Dispatchers.IO) {
                collectionManager.reorderChildren(current.collectionName, childStems)
                collectionManager.saveCollectionContents(current.collectionName, gamePaths)
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
        } else if (current.isCollection && current.collectionName != null) {
            loadCollectionInternal(current.collectionName)
        }
    }

    private fun applyCustomOrder(games: List<Game>, order: List<String>): List<Game> {
        if (order.isEmpty()) return games
        val byName = games.associateBy { it.displayName }
        val ordered = order.mapNotNull { byName[it] }
        val remaining = games.filter { it.displayName !in order }
        return ordered + remaining
    }

    private fun loadGames(tags: List<String>, subfolder: String?, preserveIndex: Int = 0, preserveScroll: Int = 0, prevCount: Int = -1) {
        val tag = tags.first()
        scope.launch(Dispatchers.IO) {
            val games = scanner.scanGames(tags, subfolder)
            val displayName = platformResolver.getDisplayName(tag)

            val breadcrumb = if (breadcrumbStack.isEmpty()) {
                displayName
            } else {
                (listOf(displayName) + breadcrumbStack)
                    .joinToString(" \u203A ")
            }

            val sameSize = prevCount >= 0 && games.size == prevCount && prevCount > 0
            val maxIdx = games.lastIndex.coerceAtLeast(0)
            val (idx, scroll) = if (sameSize || prevCount < 0) {
                preserveIndex.coerceAtMost(maxIdx) to preserveScroll.coerceAtMost(maxIdx)
            } else {
                0 to 0
            }

            _state.value = State(
                platformTag = tag,
                platformTags = tags,
                breadcrumb = breadcrumb,
                games = games,
                selectedIndex = idx,
                scrollTarget = scroll,
                subfolderPath = subfolder,
                isLoading = false
            )
        }
    }

    fun close() { scope.cancel() }
}
