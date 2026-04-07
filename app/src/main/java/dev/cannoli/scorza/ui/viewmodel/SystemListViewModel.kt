package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.CollectionManager
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.OrderingManager
import dev.cannoli.scorza.scanner.RecentlyPlayedManager
import dev.cannoli.scorza.settings.ContentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SystemListViewModel(
    private val scanner: FileScanner,
    private val collectionManager: CollectionManager,
    private val orderingManager: OrderingManager,
    private val recentlyPlayedManager: RecentlyPlayedManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    sealed class ListItem {
        data object RecentlyPlayedItem : ListItem()
        data object FavoritesItem : ListItem()
        data object CollectionsFolder : ListItem()
        data class PlatformItem(val platform: Platform) : ListItem()
        data class CollectionItem(val name: String, val count: Int) : ListItem()
        data class GameItem(val game: Game) : ListItem()
        data class ToolsFolder(val name: String, val count: Int) : ListItem()
        data class PortsFolder(val name: String, val count: Int) : ListItem()
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val isLoading: Boolean = true,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0
    private var savedPosition: Pair<Int, Int>? = null

    fun savePosition() {
        savedPosition = _state.value.selectedIndex to firstVisibleIndex
    }

    fun scan(showRecentlyPlayed: Boolean = true, showEmpty: Boolean = false, contentMode: ContentMode = ContentMode.PLATFORMS, toolsName: String = "Tools", portsName: String = "Ports") {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val restored = savedPosition
        savedPosition = null
        val prevSelectedIndex = restored?.first ?: prev.selectedIndex
        val prevFirstVisible = restored?.second ?: firstVisibleIndex

        scope.launch(Dispatchers.IO) {
            val platforms = scanner.scanPlatforms()
            val collections = collectionManager.scanCollections().map { it.stem }
            val tools = scanner.scanTools()
            val ports = scanner.scanPorts()

            val items = mutableListOf<ListItem>()

            if (contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                val fghStem = collections.firstOrNull { it.startsWith("5GH", ignoreCase = true) }
                if (fghStem != null) {
                    val games = scanner.scanCollectionGames(fghStem, includeFavoriteStars = false).take(5)
                    games.forEach { items.add(ListItem.GameItem(it)) }
                }
            } else {
                if (showRecentlyPlayed && recentlyPlayedManager.hasAny()) {
                    items.add(ListItem.RecentlyPlayedItem)
                }

                val hasFavorites = collections.any { it.equals("Favorites", ignoreCase = true) }

                if (hasFavorites) {
                    items.add(ListItem.FavoritesItem)
                }

                if (contentMode == ContentMode.PLATFORMS) {
                    val hasOtherCollections = collections.any { !it.equals("Favorites", ignoreCase = true) }
                    if (hasOtherCollections) {
                        items.add(ListItem.CollectionsFolder)
                    }
                }
            }

            val reorderableItems = mutableListOf<ListItem>()
            when (contentMode) {
                ContentMode.PLATFORMS -> {
                    val visiblePlatforms = if (showEmpty) platforms else platforms.filter { it.gameCount > 0 }
                    visiblePlatforms.forEach { reorderableItems.add(ListItem.PlatformItem(it)) }
                }
                ContentMode.COLLECTIONS -> {
                    val topLevel = collections.filter {
                        !it.equals("Favorites", ignoreCase = true) && collectionManager.isTopLevelCollection(it)
                    }
                    topLevel.forEach { stem ->
                        reorderableItems.add(ListItem.CollectionItem(stem, collectionManager.getGamePaths(stem).size))
                    }
                }
                ContentMode.FIVE_GAME_HANDHELD -> {}
            }
            if (ports.isNotEmpty()) {
                reorderableItems.add(ListItem.PortsFolder(portsName, ports.size))
            }
            if (tools.isNotEmpty()) {
                reorderableItems.add(ListItem.ToolsFolder(toolsName, tools.size))
            }
            if (reorderableItems.isNotEmpty()) {
                val ordered = applyCustomOrder(reorderableItems, orderingManager.loadPlatformOrder())
                items.addAll(ordered)
            }

            val canRestore = (restored != null || items.size == prevItemCount) && prevItemCount > 0
            val (safeIndex, scrollTo) = if (canRestore) {
                val idx = when {
                    items.isEmpty() -> 0
                    prevSelectedIndex in items.indices -> prevSelectedIndex
                    else -> items.lastIndex
                }
                idx to prevFirstVisible.coerceAtMost(items.lastIndex.coerceAtLeast(0))
            } else {
                0 to 0
            }
            _state.value = State(
                items = items,
                platforms = platforms,
                selectedIndex = safeIndex,
                scrollTarget = scrollTo,
                isLoading = false
            )
        }
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            val size = current.items.size
            if (size == 0) return@update current

            val raw = current.selectedIndex + delta
            val target = ((raw % size) + size) % size

            current.copy(selectedIndex = target)
        }
    }

    fun jumpToIndex(index: Int, scrollTarget: Int) {
        _state.update { it.copy(selectedIndex = index, scrollTarget = scrollTarget) }
    }

    fun getSelectedItem(): ListItem? {
        val current = _state.value
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedPlatformTag(): String? {
        return (getSelectedItem() as? ListItem.PlatformItem)?.platform?.tag
    }

    fun getPlatformTags(): List<String> =
        _state.value.items.filterIsInstance<ListItem.PlatformItem>().map { it.platform.tag }

    fun getNavigableItems(): List<ListItem> =
        _state.value.items

    fun enterReorderMode() {
        _state.update { current ->
            val item = current.items.getOrNull(current.selectedIndex) ?: return@update current
            if (!item.isReorderable()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val prevSelectable = (idx - 1 downTo 0).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[prevSelectable].also { items[prevSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = prevSelectable)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val nextSelectable = (idx + 1..items.lastIndex).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[nextSelectable].also { items[nextSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = nextSelectable)
        }
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        val gameItems = current.items.filterIsInstance<ListItem.GameItem>()
        if (gameItems.isNotEmpty()) {
            val fghStem = collectionManager.getCollectionStems()
                .firstOrNull { it.startsWith("5GH", ignoreCase = true) }
            if (fghStem != null) {
                val paths = gameItems.map { it.game.file.absolutePath }
                scope.launch(Dispatchers.IO) {
                    collectionManager.saveCollectionContents(fghStem, paths)
                }
            }
        }
        val tags = current.items.mapNotNull { it.orderTag() }
        if (tags.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                orderingManager.savePlatformOrder(tags)
            }
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
    }

    fun cancelReorder(showRecentlyPlayed: Boolean = true, showEmpty: Boolean = false, contentMode: ContentMode = ContentMode.PLATFORMS, toolsName: String = "Tools", portsName: String = "Ports") {
        val current = _state.value
        if (!current.reorderMode) return
        scan(showRecentlyPlayed, showEmpty, contentMode, toolsName, portsName)
    }

    private fun ListItem.isReorderable(): Boolean = this is ListItem.PlatformItem || this is ListItem.ToolsFolder || this is ListItem.PortsFolder || this is ListItem.CollectionItem || this is ListItem.GameItem

    private fun ListItem.orderTag(): String? = when (this) {
        is ListItem.PlatformItem -> platform.tag
        is ListItem.CollectionItem -> name
        is ListItem.ToolsFolder -> TAG_TOOLS
        is ListItem.PortsFolder -> TAG_PORTS
        else -> null
    }

    private fun applyCustomOrder(items: List<ListItem>, order: List<String>): List<ListItem> {
        if (order.isEmpty()) return items
        val byTag = items.associateBy { it.orderTag() }
        val ordered = mutableListOf<ListItem>()
        for (tag in order) {
            byTag[tag]?.let { ordered.add(it) }
        }
        val remaining = items.filter { it.orderTag() !in order }
        return ordered + remaining
    }

    fun close() { scope.cancel() }

    companion object {
        const val TAG_TOOLS = "__tools__"
        const val TAG_PORTS = "__ports__"
    }
}
