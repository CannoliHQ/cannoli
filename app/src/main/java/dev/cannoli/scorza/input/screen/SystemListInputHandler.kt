package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@ActivityScoped
class SystemListInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val platformConfig: PlatformConfig,
    private val updateManager: UpdateManager,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    private var selectDown = false

    override fun onUp() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
        else systemListViewModel.moveSelection(-1)
    }

    override fun onDown() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
        else systemListViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (!systemListViewModel.isReorderMode()) pageJump(-1)
    }

    override fun onRight() {
        if (!systemListViewModel.isReorderMode()) pageJump(1)
    }

    override fun onL1() = pageJump(-1)

    override fun onR1() = pageJump(1)

    override fun onConfirm() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListConfirm()
    }

    override fun onBack() {
        if (systemListViewModel.isReorderMode()) {
            systemListViewModel.cancelReorder(
                showRecentlyPlayed = settings.showRecentlyPlayed,
                contentMode = settings.contentMode,
                fghCollectionStem = launcherActions.validateFghStem(),
                toolsName = settings.toolsName,
                portsName = settings.portsName
            )
        } else if (settings.mainMenuQuit) {
            nav.dialogState.value = DialogState.QuitConfirm
        }
    }

    override fun onStart() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListContextMenu()
    }

    override fun onSelect() {
        if (selectDown) return
        selectDown = true
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else systemListViewModel.enterReorderMode()
    }

    override fun onSelectUp() {
        selectDown = false
    }

    override fun onNorth() {
        val fgh = launcherActions.validateFghStem() != null
        val item = systemListViewModel.getSelectedItem()
        if (fgh && item is SystemListViewModel.ListItem.GameItem) {
            val recentKey = item.recentKey
            val isResumable = nav.resumableGames.contains(recentKey)
            if (isResumable) {
                val errorDialog = launcherActions.launchSelected(item.item, !settings.swapPlayResume)
                if (errorDialog != null) {
                    nav.dialogState.value = errorDialog
                } else {
                    launcherActions.recordRecentlyPlayedByPath(recentKey)
                }
            }
        } else if (!fgh) {
            systemListViewModel.savePosition()
            settingsViewModel.load()
            nav.screenStack.add(LauncherScreen.Settings)
            if (updateManager.isOnline()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        }
    }

    override fun onWest() {
        if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
            systemListViewModel.savePosition()
            settingsViewModel.load()
            nav.screenStack.add(LauncherScreen.Settings)
            if (updateManager.isOnline()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            val km = dev.cannoli.scorza.server.KitchenManager
            if (km.isRunning || systemListViewModel.state.value.items.isEmpty()) {
                launcherActions.openKitchen()
            }
        }
    }

    fun handleRename(state: DialogState.RenameInput) {
        launcherActions.handleSystemListRename(state)
    }

    private fun pageJump(direction: Int) {
        val state = systemListViewModel.state.value
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

        systemListViewModel.jumpToIndex(newIdx, newScroll)
    }

    private fun onSystemListConfirm() {
        if (nav.navigating) return
        systemListViewModel.savePosition()
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                nav.navigating = true
                gameListViewModel.loadRecentlyPlayed {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                nav.navigating = true
                gameListViewModel.loadCollection("Favorites") {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                nav.navigating = true
                gameListViewModel.loadCollectionsList {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                nav.navigating = true
                gameListViewModel.loadPlatform(item.platform.tag, item.platform.allTags) {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                nav.navigating = true
                gameListViewModel.loadCollection(item.name) {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.GameItem -> {
                val recentKey = item.recentKey
                val isResumable = nav.resumableGames.contains(recentKey)
                val resume = isResumable && settings.swapPlayResume
                val errorDialog = launcherActions.launchSelected(item.item, resume)
                if (errorDialog != null) {
                    nav.dialogState.value = errorDialog
                } else {
                    launcherActions.recordRecentlyPlayedByPath(recentKey)
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                nav.navigating = true
                gameListViewModel.loadApkList("tools", item.name) {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                nav.navigating = true
                gameListViewModel.loadApkList("ports", item.name) {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            else -> {}
        }
    }

    private fun onSystemListContextMenu() {
        val item = systemListViewModel.getSelectedItem() ?: return
        if (item is SystemListViewModel.ListItem.GameItem) {
            val ref = resolveItemRef(item)
            val isFav = ref?.let { r ->
                when (r) {
                    is dev.cannoli.scorza.db.LibraryRef.Rom -> collectionsRepository.isRomFavorited(r.id)
                    is dev.cannoli.scorza.db.LibraryRef.App -> collectionsRepository.isAppFavorited(r.id)
                }
            } == true
            nav.pendingFghItem = item.item
            val menuName = item.displayName
            val options = buildList {
                add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_EMULATOR_OVERRIDE)
                add(MENU_DELETE_GAME)
            }
            nav.dialogState.value = DialogState.ContextMenu(gameName = menuName, options = options)
            return
        }
        val name = when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> item.platform.displayName
            is SystemListViewModel.ListItem.ToolsFolder -> item.name
            is SystemListViewModel.ListItem.PortsFolder -> item.name
            else -> return
        }
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = name,
            options = listOf(MENU_RENAME)
        )
    }

    private fun onSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.gameName) {
            nav.dialogState.value = DialogState.None
            return
        }
        val item = systemListViewModel.getSelectedItem()
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformConfig.setDisplayName(item.platform.tag, newName)
                    launcherActions.rescanSystemList()
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                launcherActions.rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                launcherActions.rescanSystemList()
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                ioScope.launch {
                    val id = collectionsRepository.all().firstOrNull { it.displayName == item.name }?.id
                    if (id != null) collectionsRepository.rename(id, newName)
                    launcherActions.rescanSystemList()
                }
            }
            else -> {}
        }
        nav.dialogState.value = DialogState.None
    }

    private fun resolveItemRef(item: SystemListViewModel.ListItem.GameItem): dev.cannoli.scorza.db.LibraryRef? {
        return when (val inner = item.item) {
            is ListItem.RomItem -> dev.cannoli.scorza.db.LibraryRef.Rom(inner.rom.id)
            is ListItem.AppItem -> dev.cannoli.scorza.db.LibraryRef.App(inner.app.id)
            else -> null
        }
    }

    companion object {
        private const val MENU_RENAME = "Rename"
        private const val MENU_DELETE_GAME = "Delete Game"
        private const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
        private const val MENU_EMULATOR_OVERRIDE = "Emulator Override"
        private const val MENU_ADD_FAVORITE = "Add To Favorites"
        private const val MENU_REMOVE_FAVORITE = "Remove From Favorites"
    }
}
