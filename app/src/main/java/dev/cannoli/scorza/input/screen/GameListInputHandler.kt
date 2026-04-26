package dev.cannoli.scorza.input.screen

import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.recentKey
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import kotlinx.coroutines.CoroutineScope

class GameListInputHandler(
    private val nav: NavigationController,
    private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val isSelectHeld: () -> Boolean,
    private val onRescanSystemList: () -> Unit,
    private val onScanResumableGames: () -> Unit,
    private val onLaunchSelected: (item: ListItem, resume: Boolean) -> DialogState?,
    private val onRecordRecentlyPlayedByPath: (path: String) -> Unit,
    private val onSetPendingRecentlyPlayedReorder: (Boolean) -> Unit,
    private val onBuildContextOptions: (item: ListItem, glState: GameListViewModel.State) -> List<String>,
) : ScreenInputHandler {

    var selectHandled = false
    private var collectionSelectHeld = false
    private var gameSelectDown = false
    private val selectHoldHandler = Handler(Looper.getMainLooper())
    val collectionSelectHoldRunnable = Runnable {
        collectionSelectHeld = true
        val glState = gameListViewModel.state.value
        val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
        if ((glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) || isApkList) {
            if (!gameListViewModel.isReorderMode() && !gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.enterMultiSelect()
            }
        }
    }

    fun postSelectHoldTimer() {
        selectHoldHandler.postDelayed(collectionSelectHoldRunnable, 400)
    }

    fun cancelSelectHoldTimer() {
        selectHoldHandler.removeCallbacks(collectionSelectHoldRunnable)
    }

    override fun onUp() {
        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
        else gameListViewModel.moveSelection(-1)
    }

    override fun onDown() {
        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
        else gameListViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (!gameListViewModel.isReorderMode()) pageJump(-1)
    }

    override fun onRight() {
        if (!gameListViewModel.isReorderMode()) pageJump(1)
    }

    override fun onConfirm() {
        when {
            gameListViewModel.isMultiSelectMode() -> gameListViewModel.toggleChecked()
            gameListViewModel.isReorderMode() -> gameListViewModel.confirmReorder()
            else -> onGameListConfirm()
        }
    }

    override fun onBack() {
        when {
            gameListViewModel.isMultiSelectMode() -> gameListViewModel.cancelMultiSelect()
            gameListViewModel.isReorderMode() -> gameListViewModel.cancelReorder()
            !nav.navigating -> {
                val glState = gameListViewModel.state.value
                if (!gameListViewModel.exitSubfolder()) {
                    if (gameListViewModel.exitChildCollection { onScanResumableGames() }) {
                        // navigated back to parent collection
                    } else if (settings.contentMode == ContentMode.PLATFORMS
                        && glState.isCollection && glState.collectionName != null
                        && !glState.collectionName.equals("Favorites", ignoreCase = true)) {
                        gameListViewModel.loadCollectionsList(restoreIndex = true)
                    } else {
                        nav.screenStack.removeAt(nav.screenStack.lastIndex)
                        onRescanSystemList()
                    }
                }
            }
        }
    }

    override fun onStart() {
        val glState = gameListViewModel.state.value
        if (gameListViewModel.isMultiSelectMode()) {
            val checkedItems: List<ListItem> = glState.checkedIndices
                .mapNotNull { glState.items.getOrNull(it) }
                .filter { it !is ListItem.SubfolderItem && it !is ListItem.ChildCollectionItem }
            if (checkedItems.isNotEmpty()) {
                val paths = checkedItems.mapNotNull { it.recentKey() }
                val allFav = paths.all { path ->
                    val ref = resolveRef(path, glState) ?: return@all false
                    when (ref) {
                        is FavRef.Rom -> ref.id in glState.favoriteRomIds
                        is FavRef.App -> ref.id in glState.favoriteAppIds
                    }
                }
                val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
                val options = mutableListOf<String>()
                if (glState.platformTag == "recently_played") options.add(MENU_REMOVE_FROM_RECENTS)
                options.add(if (allFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                if (glState.isCollection && glState.collectionName != null) {
                    options.add(MENU_REMOVE_FROM_COLLECTION)
                }
                if (isApkList) {
                    options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_REMOVE))
                } else {
                    options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_DELETE_ART, MENU_DELETE_GAME))
                }
                gameListViewModel.confirmMultiSelect()
                nav.dialogState.value = DialogState.BulkContextMenu(
                    gamePaths = paths,
                    options = options
                )
            } else {
                gameListViewModel.cancelMultiSelect()
            }
        } else if (gameListViewModel.isReorderMode()) {
            gameListViewModel.confirmReorder()
        } else {
            val item = gameListViewModel.getSelectedItem()
            if (item != null) {
                val menuName = when (item) {
                    is ListItem.RomItem -> item.rom.displayName
                    is ListItem.AppItem -> item.app.displayName
                    is ListItem.SubfolderItem -> item.name
                    is ListItem.CollectionItem -> item.collection.displayName
                    is ListItem.ChildCollectionItem -> item.collection.displayName
                }
                nav.dialogState.value = DialogState.ContextMenu(
                    gameName = menuName,
                    options = onBuildContextOptions(item, glState)
                )
            }
        }
    }

    override fun onSelect() {
        if (gameSelectDown) return
        gameSelectDown = true
        val glState = gameListViewModel.state.value
        val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
        if (glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) {
            if (gameListViewModel.isReorderMode()) {
                gameListViewModel.confirmReorder()
                selectHandled = true
            } else if (gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.confirmMultiSelect()
                selectHandled = true
            } else {
                postSelectHoldTimer()
            }
        } else if (isApkList) {
            if (gameListViewModel.isReorderMode()) {
                gameListViewModel.confirmReorder()
                selectHandled = true
            } else if (gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.confirmMultiSelect()
                selectHandled = true
            } else {
                postSelectHoldTimer()
            }
        } else if (glState.isCollectionsList) {
            if (gameListViewModel.isReorderMode()) {
                gameListViewModel.confirmReorder()
            } else {
                gameListViewModel.enterReorderMode()
            }
        } else if (glState.subfolderPath == null) {
            if (gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.confirmMultiSelect()
            } else {
                gameListViewModel.enterMultiSelect()
            }
        }
    }

    override fun onSelectUp() {
        cancelSelectHoldTimer()
        if (!isSelectHeld() && !collectionSelectHeld && !selectHandled) {
            val glState = gameListViewModel.state.value
            val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
            if (((glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) || isApkList)
                && !gameListViewModel.isReorderMode() && !gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.enterReorderMode()
            }
        }
        selectHandled = false
        collectionSelectHeld = false
        gameSelectDown = false
    }

    override fun onNorth() {
        val glState = gameListViewModel.state.value
        if (glState.isCollectionsList) return
        val item = gameListViewModel.getSelectedItem() ?: return
        val recentKey = selectedRecentKey(item) ?: return
        val isResumable = nav.resumableGames.contains(recentKey)
        if (isResumable) {
            val trackRecent = glState.platformTag != "tools"
            val errorDialog = onLaunchSelected(item, !settings.swapPlayResume)
            if (errorDialog != null) {
                nav.dialogState.value = errorDialog
            } else if (trackRecent) {
                onRecordRecentlyPlayedByPath(recentKey)
            }
        }
    }

    override fun onWest() {
        val glState = gameListViewModel.state.value
        if (glState.isCollectionsList) {
            nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList())
        } else if (glState.isCollection && glState.collectionName != null) {
            nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList(), parentStem = glState.collectionName)
        }
    }

    override fun onL1() {
        if (settings.platformSwitching) switchPlatform(-1)
    }

    override fun onR1() {
        if (settings.platformSwitching) switchPlatform(1)
    }

    private fun onGameListConfirm() {
        if (nav.navigating) return
        val item = gameListViewModel.getSelectedItem() ?: return

        when (item) {
            is ListItem.CollectionItem -> {
                nav.navigating = true
                gameListViewModel.loadCollection(item.collection.displayName) {
                    onScanResumableGames()
                    nav.navigating = false
                }
                return
            }
            is ListItem.ChildCollectionItem -> {
                nav.navigating = true
                gameListViewModel.enterChildCollection(item.collection.displayName) {
                    onScanResumableGames()
                    nav.navigating = false
                }
                return
            }
            is ListItem.SubfolderItem -> {
                gameListViewModel.enterSubfolder(item.name)
                return
            }
            else -> {}
        }

        val recentKey = selectedRecentKey(item) ?: return
        val isResumable = nav.resumableGames.contains(recentKey)
        val tag = gameListViewModel.state.value.platformTag
        val trackRecent = tag != "tools"
        val errorDialog = onLaunchSelected(item, isResumable && settings.swapPlayResume)
        if (errorDialog != null) {
            nav.dialogState.value = errorDialog
        } else if (trackRecent) {
            onRecordRecentlyPlayedByPath(recentKey)
            if (tag == "recently_played") onSetPendingRecentlyPlayedReorder(true)
        }
    }

    private fun switchPlatform(delta: Int) {
        if (nav.navigating) return
        val items = systemListViewModel.getNavigableItems()
        if (items.size < 2) return

        val gs = gameListViewModel.state.value
        val currentIndex = items.indexOfFirst { item ->
            when {
                gs.platformTag == "recently_played" -> item is SystemListViewModel.ListItem.RecentlyPlayedItem
                gs.isCollectionsList -> item is SystemListViewModel.ListItem.CollectionsFolder
                gs.isCollection && gs.collectionName.equals("Favorites", ignoreCase = true) -> item is SystemListViewModel.ListItem.FavoritesItem
                gs.isCollection && gs.collectionName != null -> {
                    (item is SystemListViewModel.ListItem.CollectionsFolder) ||
                    (item is SystemListViewModel.ListItem.CollectionItem && item.name == gs.collectionName)
                }
                gs.platformTag == "tools" -> item is SystemListViewModel.ListItem.ToolsFolder
                gs.platformTag == "ports" -> item is SystemListViewModel.ListItem.PortsFolder
                gs.platformTag.isNotEmpty() -> item is SystemListViewModel.ListItem.PlatformItem && item.platform.tag == gs.platformTag
                else -> false
            }
        }
        if (currentIndex == -1) return

        val newIndex = (currentIndex + delta).mod(items.size)
        nav.navigating = true
        when (val target = items[newIndex]) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                gameListViewModel.loadRecentlyPlayed {
                    onScanResumableGames()
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                gameListViewModel.loadCollection("Favorites") {
                    onScanResumableGames()
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                gameListViewModel.loadCollectionsList {
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                gameListViewModel.loadPlatform(target.platform.tag, target.platform.allTags) {
                    onScanResumableGames()
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                gameListViewModel.loadCollection(target.name) {
                    onScanResumableGames()
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                gameListViewModel.loadApkList("tools", target.name) {
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                gameListViewModel.loadApkList("ports", target.name) {
                    nav.navigating = false
                }
            }
            else -> { nav.navigating = false }
        }
    }

    private fun pageJump(direction: Int) {
        val state = gameListViewModel.state.value
        val itemCount = state.items.size
        if (itemCount == 0) return
        val lastIndex = itemCount - 1
        val page = nav.currentPageSize.coerceAtLeast(1)

        val newIdx: Int
        val newScroll: Int

        if (direction > 0) {
            val lastVisible = nav.currentFirstVisible + page - 1
            if (lastVisible >= lastIndex) {
                if (state.selectedIndex >= lastIndex) return
                newIdx = lastIndex
                newScroll = nav.currentFirstVisible
            } else {
                newIdx = (nav.currentFirstVisible + page).coerceAtMost(lastIndex)
                newScroll = newIdx
            }
        } else {
            if (nav.currentFirstVisible <= 0) {
                if (state.selectedIndex <= 0) return
                newIdx = 0
                newScroll = 0
            } else {
                newIdx = (nav.currentFirstVisible - page).coerceAtLeast(0)
                newScroll = newIdx
            }
        }

        gameListViewModel.jumpToIndex(newIdx, newScroll)
    }

    private fun selectedRecentKey(item: ListItem): String? = when (item) {
        is ListItem.RomItem -> item.rom.path.absolutePath
        is ListItem.AppItem -> "/apps/${item.app.type.name}/${item.app.packageName}"
        else -> null
    }

    private sealed interface FavRef {
        data class Rom(val id: Long) : FavRef
        data class App(val id: Long) : FavRef
    }

    private fun resolveRef(path: String, glState: GameListViewModel.State): FavRef? {
        if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size != 2) return null
            return glState.items
                .filterIsInstance<ListItem.AppItem>()
                .firstOrNull { "/apps/${it.app.type.name}/${it.app.packageName}" == path }
                ?.let { FavRef.App(it.app.id) }
        }
        return glState.items
            .filterIsInstance<ListItem.RomItem>()
            .firstOrNull { it.rom.path.absolutePath == path }
            ?.let { FavRef.Rom(it.rom.id) }
    }

    companion object {
        private const val MENU_REMOVE_FROM_RECENTS = "Remove From Recently Played"
        private const val MENU_ADD_FAVORITE = "Add To Favorites"
        private const val MENU_REMOVE_FAVORITE = "Remove From Favorites"
        private const val MENU_REMOVE_FROM_COLLECTION = "Remove From Collection"
        private const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
        private const val MENU_REMOVE = "Remove Shortcut"
        private const val MENU_DELETE_ART = "Delete Art"
        private const val MENU_DELETE_GAME = "Delete Game"
    }
}
