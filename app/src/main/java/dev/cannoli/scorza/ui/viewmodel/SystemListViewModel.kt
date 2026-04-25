package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.library.AppsRepository
import dev.cannoli.scorza.library.CollectionsRepository
import dev.cannoli.scorza.library.RecentlyPlayedRepository
import dev.cannoli.scorza.library.RomLibrary
import dev.cannoli.scorza.library.RomScanner
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.settings.ContentMode
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

class SystemListViewModel(
    private val romLibrary: RomLibrary,
    private val romScanner: RomScanner,
    private val appsRepository: AppsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val platformConfig: PlatformConfig,
    private val romDirectory: File,
    private val appPackageToFile: (App) -> File = { File("/apps/${it.type.name}/${it.packageName}") },
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
        val reorderOriginalIndex: Int = -1,
        val hasGameItems: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0
    private var savedPosition: Pair<Int, Int>? = null
    private var currentFghStem: String? = null
    private var currentFghCollectionId: Long? = null

    fun savePosition() {
        savedPosition = _state.value.selectedIndex to firstVisibleIndex
    }

    fun scan(showRecentlyPlayed: Boolean = true, showEmpty: Boolean = false, contentMode: ContentMode = ContentMode.PLATFORMS, fghCollectionStem: String? = null, toolsName: String = "Tools", portsName: String = "Ports", onReady: () -> Unit = {}) {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val restored = savedPosition
        savedPosition = null
        val prevSelectedIndex = restored?.first ?: prev.selectedIndex
        val prevFirstVisible = restored?.second ?: firstVisibleIndex
        currentFghStem = fghCollectionStem

        scope.launch(Dispatchers.IO) {
            scanAllPlatformDirs()
            val countsByTag = romLibrary.platformCounts().mapKeys { it.key.uppercase() }
            val knownTagsInDb = romLibrary.knownPlatformTags()
            val knownTags = (knownTagsInDb + countsByTag.keys).distinct()
                .filter { it != TAG_TOOLS && it != TAG_PORTS }

            val allPlatforms = knownTags
                .filter { platformConfig.isKnownTag(it) }
                .map { tag ->
                    val count = countsByTag[tag] ?: 0
                    platformConfig.resolvePlatform(tag, romDirectory, count)
                }

            val groupedPlatforms = allPlatforms.groupBy { it.displayName }.map { (_, group) ->
                if (group.size == 1) group[0]
                else {
                    val primary = group.maxBy { it.gameCount }
                    primary.copy(
                        gameCount = group.sumOf { it.gameCount },
                        tags = group.map { it.tag }
                    )
                }
            }

            val toolCount = appsRepository.count(AppType.TOOL)
            val portCount = appsRepository.count(AppType.PORT)

            val items = mutableListOf<ListItem>()
            val collections = collectionsRepository.all()
            val favoritesId = collectionsRepository.favoritesId()

            if (contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                if (fghCollectionStem != null) {
                    val fghId = collections.firstOrNull { it.displayName.equals(fghCollectionStem, ignoreCase = true) }?.id
                    currentFghCollectionId = fghId
                    if (fghId != null) {
                        collectionsRepository.romIdsIn(fghId)
                            .mapNotNull { romLibrary.gameById(it) }
                            .forEach { rom -> items.add(ListItem.GameItem(romToGame(rom))) }
                        collectionsRepository.appIdsIn(fghId)
                            .mapNotNull { appsRepository.byId(it) }
                            .forEach { app -> items.add(ListItem.GameItem(appToGame(app))) }
                    }
                } else {
                    currentFghCollectionId = null
                }
            } else {
                currentFghCollectionId = null
                if (showRecentlyPlayed && recentlyPlayedRepository.hasAny()) {
                    items.add(ListItem.RecentlyPlayedItem)
                }
                if (favoritesId != null) {
                    items.add(ListItem.FavoritesItem)
                }
                if (contentMode == ContentMode.PLATFORMS) {
                    val hasTopLevelStandard = collectionsRepository.topLevel().isNotEmpty()
                    if (hasTopLevelStandard) {
                        items.add(ListItem.CollectionsFolder)
                    }
                }
            }

            val reorderableItems = mutableListOf<ListItem>()
            when (contentMode) {
                ContentMode.PLATFORMS -> {
                    val visiblePlatforms = if (showEmpty) groupedPlatforms else groupedPlatforms.filter { it.gameCount > 0 }
                    visiblePlatforms.sortedBy { it.displayName }.forEach {
                        reorderableItems.add(ListItem.PlatformItem(it))
                    }
                }
                ContentMode.COLLECTIONS -> {
                    collectionsRepository.topLevel().forEach { row ->
                        val count = collectionsRepository.romIdsIn(row.id).size + collectionsRepository.appIdsIn(row.id).size
                        reorderableItems.add(ListItem.CollectionItem(row.displayName, count))
                    }
                }
                ContentMode.FIVE_GAME_HANDHELD -> {}
            }
            if (contentMode != ContentMode.FIVE_GAME_HANDHELD) {
                if (portCount > 0) reorderableItems.add(ListItem.PortsFolder(portsName, portCount))
                if (toolCount > 0) reorderableItems.add(ListItem.ToolsFolder(toolsName, toolCount))
            }
            if (reorderableItems.isNotEmpty()) {
                val ordered = applyCustomOrder(reorderableItems, romLibrary.knownPlatformTags())
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
                platforms = groupedPlatforms,
                selectedIndex = safeIndex,
                scrollTarget = scrollTo,
                isLoading = false,
                hasGameItems = items.any { it is ListItem.GameItem }
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    private fun scanAllPlatformDirs() {
        if (!romDirectory.exists()) return
        val tagDirs = romDirectory.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
        for (dir in tagDirs) {
            val tag = dir.name.uppercase()
            if (!platformConfig.isKnownTag(tag)) continue
            romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
        }
    }

    private fun romToGame(rom: dev.cannoli.scorza.model.Rom): Game = Game(
        file = rom.path,
        displayName = rom.displayName,
        platformTag = rom.platformTag,
        artFile = rom.artFile,
        launchTarget = rom.launchTarget,
        discFiles = rom.discFiles,
    )

    private fun appToGame(app: App): Game = Game(
        file = appPackageToFile(app),
        displayName = app.displayName,
        platformTag = if (app.type == AppType.TOOL) "tools" else "ports",
        launchTarget = LaunchTarget.ApkLaunch(app.packageName),
    )

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
        val collectionItems = current.items.filterIsInstance<ListItem.CollectionItem>()
        if (gameItems.isNotEmpty()) {
            val fghId = currentFghCollectionId
            if (fghId != null) {
                val refs = gameItems.mapNotNull { gameItem ->
                    val game = gameItem.game
                    if (game.platformTag == "tools" || game.platformTag == "ports") {
                        val pkg = (game.launchTarget as? LaunchTarget.ApkLaunch)?.packageName
                        val type = if (game.platformTag == "tools") AppType.TOOL else AppType.PORT
                        pkg?.let { appsRepository.byPackage(type, it) }?.let { dev.cannoli.scorza.library.LibraryRef.App(it.id) }
                    } else {
                        romLibrary.gameByPath(game.file.absolutePath)?.let { dev.cannoli.scorza.library.LibraryRef.Rom(it.id) }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    collectionsRepository.setMemberOrder(fghId, refs)
                }
            }
        } else if (collectionItems.isNotEmpty()) {
            val orderedIds = collectionItems.mapNotNull { ci ->
                collectionsRepository.all().firstOrNull { it.displayName == ci.name }?.id
            }
            scope.launch(Dispatchers.IO) {
                collectionsRepository.setCollectionOrder(orderedIds)
            }
        } else {
            val tags = current.items.mapNotNull { it.orderTag() }
            if (tags.isNotEmpty()) {
                ensureReservedTag(TAG_TOOLS)
                ensureReservedTag(TAG_PORTS)
                scope.launch(Dispatchers.IO) {
                    romLibrary.setPlatformOrder(tags)
                }
            }
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
    }

    private fun ensureReservedTag(tag: String) {
        romScanner.ensureReservedPlatformTag(tag)
    }

    fun cancelReorder(showRecentlyPlayed: Boolean = true, showEmpty: Boolean = false, contentMode: ContentMode = ContentMode.PLATFORMS, fghCollectionStem: String? = null, toolsName: String = "Tools", portsName: String = "Ports") {
        val current = _state.value
        if (!current.reorderMode) return
        scan(showRecentlyPlayed, showEmpty, contentMode, fghCollectionStem, toolsName, portsName)
    }

    private fun ListItem.isReorderable(): Boolean = this is ListItem.PlatformItem || this is ListItem.ToolsFolder || this is ListItem.PortsFolder || this is ListItem.CollectionItem || this is ListItem.GameItem

    private fun ListItem.orderTag(): String? = when (this) {
        is ListItem.PlatformItem -> platform.tag
        is ListItem.CollectionItem -> name
        is ListItem.ToolsFolder -> TAG_TOOLS
        is ListItem.PortsFolder -> TAG_PORTS
        is ListItem.GameItem -> null
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
        const val TAG_TOOLS = "__TOOLS__"
        const val TAG_PORTS = "__PORTS__"
    }
}
