package dev.cannoli.scorza.input.screen

import android.os.Handler
import android.os.Looper
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.MENU_ADD_FAVORITE
import dev.cannoli.scorza.input.MENU_DELETE_ART
import dev.cannoli.scorza.input.MENU_DELETE_GAME
import dev.cannoli.scorza.input.MENU_MANAGE_COLLECTIONS
import dev.cannoli.scorza.input.MENU_PRELOAD_ACHIEVEMENTS
import dev.cannoli.scorza.input.MENU_REMOVE
import dev.cannoli.scorza.input.MENU_REMOVE_FAVORITE
import dev.cannoli.scorza.input.MENU_REMOVE_FROM_COLLECTION
import dev.cannoli.scorza.input.MENU_REMOVE_FROM_RECENTS
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.recentKey
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.input.PageJump
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@ActivityScoped
class GameListInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val gameListViewModel: GameListViewModel,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    var buildContextOptions: ((item: ListItem, glState: GameListViewModel.State) -> List<String>)? = null

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

    private enum class ResumeButton { CONFIRM, NORTH }
    private var armedResumeButton: ResumeButton? = null
    private var resumeHoldFired = false
    private val resumeHoldHandler = Handler(Looper.getMainLooper())
    val resumeHoldRunnable = Runnable {
        val button = armedResumeButton ?: return@Runnable
        armedResumeButton = null
        resumeHoldFired = true
        val item = gameListViewModel.getSelectedItem() as? ListItem.RomItem ?: return@Runnable
        nav.push(launcherActions.buildSaveStatePicker(item.rom, awaitConfirmRelease = button == ResumeButton.CONFIRM))
    }

    private fun armResumeHold(button: ResumeButton) {
        if (armedResumeButton == button) return
        resumeHoldHandler.removeCallbacks(resumeHoldRunnable)
        armedResumeButton = button
        resumeHoldFired = false
        resumeHoldHandler.postDelayed(resumeHoldRunnable, 400)
    }

    private fun cancelResumeHoldTimer() {
        resumeHoldHandler.removeCallbacks(resumeHoldRunnable)
        armedResumeButton = null
        resumeHoldFired = false
    }

    private fun isResumableRomSelected(): Boolean {
        val item = gameListViewModel.getSelectedItem() ?: return false
        if (item !is ListItem.RomItem) return false
        val key = selectedRecentKey(item) ?: return false
        return nav.resumableGames.contains(key)
    }

    override fun onUp() {
        cancelResumeHoldTimer()
        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
        else gameListViewModel.moveSelection(-1)
    }

    override fun onDown() {
        cancelResumeHoldTimer()
        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
        else gameListViewModel.moveSelection(1)
    }

    override fun onLeft() {
        cancelResumeHoldTimer()
        if (!gameListViewModel.isReorderMode()) pageJump(-1)
    }

    override fun onRight() {
        cancelResumeHoldTimer()
        if (!gameListViewModel.isReorderMode()) pageJump(1)
    }

    override fun onConfirm() {
        when {
            gameListViewModel.isMultiSelectMode() -> gameListViewModel.toggleChecked()
            gameListViewModel.isReorderMode() -> gameListViewModel.confirmReorder()
            settings.swapPlayResume && isResumableRomSelected() -> armResumeHold(ResumeButton.CONFIRM)
            else -> onGameListConfirm()
        }
    }

    override fun onConfirmUp() {
        if (armedResumeButton != ResumeButton.CONFIRM) return
        val fired = resumeHoldFired
        cancelResumeHoldTimer()
        if (!fired) onGameListConfirm()
    }

    override fun onR1() {
        val glState = gameListViewModel.state.value
        if (glState.reorderMode || glState.multiSelectMode || glState.isCollectionsList) return
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "launcher_search",
            searchScope = glState.breadcrumb,
            keyboard = KeyboardState(
                text = glState.searchTerm ?: "",
                cursorPos = (glState.searchTerm ?: "").length,
            ),
        )
    }

    override fun onBack() {
        cancelResumeHoldTimer()
        when {
            gameListViewModel.isMultiSelectMode() -> gameListViewModel.cancelMultiSelect()
            gameListViewModel.isReorderMode() -> gameListViewModel.cancelReorder()
            gameListViewModel.isSearching() -> gameListViewModel.clearSearch()
            !nav.navigating -> {
                val glState = gameListViewModel.state.value
                if (!gameListViewModel.exitSubfolder()) {
                    if (gameListViewModel.exitChildCollection { launcherActions.scanResumableGames() }) {
                        // navigated back to parent collection
                    } else if (settings.contentMode == ContentMode.PLATFORMS
                        && glState.isCollection && glState.collectionId != null
                        && !glState.isFavorites) {
                        gameListViewModel.loadCollectionsList(restoreIndex = true)
                    } else {
                        nav.screenStack.removeAt(nav.screenStack.lastIndex)
                        launcherActions.rescanSystemList()
                    }
                }
            }
        }
    }

    override fun onStart() {
        cancelResumeHoldTimer()
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
                if (glState.isCollection && glState.collectionId != null) {
                    options.add(MENU_REMOVE_FROM_COLLECTION)
                }
                if (isApkList) {
                    options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_REMOVE))
                } else {
                    options.add(MENU_MANAGE_COLLECTIONS)
                    if (settings.raToken.isNotEmpty() &&
                        dev.cannoli.scorza.ra.RaConsoles.MAP.containsKey(glState.platformTag.uppercase())
                    ) {
                        options.add(MENU_PRELOAD_ACHIEVEMENTS)
                    }
                    options.addAll(listOf(MENU_DELETE_ART, MENU_DELETE_GAME))
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
                    options = buildContextOptions?.invoke(item, glState) ?: emptyList()
                )
            }
        }
    }

    override fun onSelect() {
        cancelResumeHoldTimer()
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
        // gameSelectDown gates the action: an open dialog consumes the press but not the release,
        // so a release can arrive here with no press behind it. Flags below still reset either way.
        if (gameSelectDown && !nav.selectHeld && !collectionSelectHeld && !selectHandled) {
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
        if (!settings.swapPlayResume && isResumableRomSelected()) {
            armResumeHold(ResumeButton.NORTH)
        } else {
            northAction()
        }
    }

    override fun onNorthUp() {
        if (armedResumeButton != ResumeButton.NORTH) return
        val fired = resumeHoldFired
        cancelResumeHoldTimer()
        if (!fired) northAction()
    }

    private fun northAction() {
        val glState = gameListViewModel.state.value
        if (glState.isCollectionsList) return
        val item = gameListViewModel.getSelectedItem() ?: return
        val recentKey = selectedRecentKey(item) ?: return
        val isResumable = nav.resumableGames.contains(recentKey)
        if (isResumable) {
            val trackRecent = glState.platformTag != "tools"
            val errorDialog = launcherActions.launchSelected(item, !settings.swapPlayResume)
            if (errorDialog != null) {
                nav.dialogState.value = errorDialog
            } else if (nav.dialogState.value is DialogState.SaveSyncChecking) {
                if (trackRecent) launcherActions.recordPendingRecent(recentKey, false)
            } else if (trackRecent) {
                launcherActions.recordRecentlyPlayedByPath(recentKey)
            }
        }
    }

    override fun onWest() {
        val glState = gameListViewModel.state.value
        if (glState.isCollectionsList) {
            nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList())
        } else if (glState.isCollection && glState.collectionId != null && !glState.isFavorites) {
            nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList(), parentId = glState.collectionId)
        }
    }

    private fun onGameListConfirm() {
        if (nav.navigating) return
        val item = gameListViewModel.getSelectedItem() ?: return

        when (item) {
            is ListItem.CollectionItem -> {
                nav.navigating = true
                gameListViewModel.loadCollectionById(item.collection.id) {
                    launcherActions.scanResumableGames()
                    nav.navigating = false
                }
                return
            }
            is ListItem.ChildCollectionItem -> {
                nav.navigating = true
                gameListViewModel.enterChildCollectionById(item.collection.id) {
                    launcherActions.scanResumableGames()
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
        val errorDialog = launcherActions.launchSelected(item, isResumable && settings.swapPlayResume)
        if (errorDialog != null) {
            nav.dialogState.value = errorDialog
        } else if (nav.dialogState.value is DialogState.SaveSyncChecking) {
            if (trackRecent) launcherActions.recordPendingRecent(recentKey, tag == "recently_played")
        } else if (trackRecent) {
            launcherActions.recordRecentlyPlayedByPath(recentKey)
            if (tag == "recently_played") nav.pendingRecentlyPlayedReorder = true
        }
    }

    private fun pageJump(direction: Int) {
        val state = gameListViewModel.state.value
        val newIdx = PageJump.compute(direction, state.items.size, state.selectedIndex, nav.activeListState)
        if (newIdx != state.selectedIndex) gameListViewModel.setSelectedIndex(newIdx)
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

}
