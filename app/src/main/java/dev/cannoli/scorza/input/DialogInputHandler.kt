package dev.cannoli.scorza.input

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.recentKey
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.download.DownloadStatus
import dev.cannoli.scorza.romm.download.inDisplayOrder
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.EmulatorPickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.withMenuDelta
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.components.Direction
import dev.cannoli.ui.components.HEX_KEYS
import dev.cannoli.ui.components.HEX_ROW_SIZE
import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardPress
import dev.cannoli.ui.components.KeyboardState
import dev.cannoli.ui.theme.COLOR_PRESETS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@ActivityScoped
class DialogInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    @ActivityContext private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val collectionManager: CollectionsRepository,
    private val recentlyPlayedManager: RecentlyPlayedRepository,
    private val platformResolver: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val launchManager: LaunchManager,
    private val updateManager: dev.cannoli.scorza.updater.UpdateManager,
    private val atomicRename: AtomicRename,
    private val scanner: RomScanner,
    private val settingsViewModel: SettingsViewModel,
    private val gameListViewModel: GameListViewModel,
    private val systemListViewModel: SystemListViewModel,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val launcherActions: LauncherActions,
    private val activityActions: ActivityActions,
    private val controllersViewModel: dev.cannoli.scorza.ui.viewmodel.ControllersViewModel,
    private val emulatorMappingBuilder: EmulatorMappingBuilder,
    private val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
    private val rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader,
    private val rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel,
    private val rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher,
    private val raPreloadController: dev.cannoli.scorza.ra.RaPreloadController,
    private val deviceRegistrar: dev.cannoli.scorza.romm.sync.DeviceRegistrar,
    private val saveSyncService: dev.cannoli.scorza.romm.sync.SaveSyncService,
    private val slotManager: dev.cannoli.scorza.romm.sync.SlotManager,
    private val saveSlotsHandler: dev.cannoli.scorza.input.screen.SaveSlotsInputHandler,
) : DialogPrecedence {
    private val selectHoldHandler = Handler(Looper.getMainLooper())
    private val selectHoldRunnable = Runnable {
        nav.selectHeld = true
        val ds = nav.dialogState.value
        if (ds is KeyboardHost && ds.keyboard.layout.supportsSymbols) {
            val ks = ds.keyboard
            if (!ks.symbols) nav.capsBeforeSymbols = ks.caps
            nav.dialogState.value = ds.withKeyboard(ks.copy(caps = false, symbols = !ks.symbols))
        }
    }

    override fun cancelSelectHold() {
        selectHoldHandler.removeCallbacks(selectHoldRunnable)
    }

    override fun onMenu(): Boolean {
        if (nav.dialogState.value != DialogState.None) return false
        if (isRommScreen()) {
            nav.dialogState.value = DialogState.RommActionsMenu(
                hasDownloads = rommDownloader.queue.state.value.isNotEmpty(),
            )
            return true
        }
        if (!isLauncherHomeScreen()) return false
        val rows = dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.visibleRows(
            rommPaired = rommStore.isConfigured,
            kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
        )
        nav.dialogState.value = DialogState.QuickMenu(rows = rows, kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning)
        return true
    }

    private fun isLauncherHomeScreen(): Boolean = when (nav.currentScreen) {
        is LauncherScreen.SystemList,
        is LauncherScreen.GameList -> true
        else -> false
    }

    private fun isRommScreen(): Boolean = when (nav.currentScreen) {
        is LauncherScreen.RommPlatformList,
        is LauncherScreen.RommGameList,
        is LauncherScreen.RommGlobalSearch,
        is LauncherScreen.RommFirmwareList,
        is LauncherScreen.RommCollectionList,
        is LauncherScreen.RommCollectionGameList,
        is LauncherScreen.RommGameDetail -> true
        else -> false
    }

    private fun cycleRommSettings(ds: DialogState.RommSettingsMenu, delta: Int) {
        when (dev.cannoli.scorza.ui.components.RommSettingsRow.entries.getOrNull(ds.selectedIndex)) {
            dev.cannoli.scorza.ui.components.RommSettingsRow.CONCURRENT -> {
                val next = ((ds.concurrent - 1 + delta + 4) % 4) + 1
                settings.concurrentDownloads = next
                nav.dialogState.value = ds.copy(concurrent = settings.concurrentDownloads)
            }
            dev.cannoli.scorza.ui.components.RommSettingsRow.COVER_ART -> {
                val types = dev.cannoli.scorza.romm.RommArtType.entries
                val next = types[(ds.artType.ordinal + delta + types.size) % types.size]
                rommStore.artType = next
                nav.dialogState.value = ds.copy(artType = next)
            }
            else -> {}
        }
    }

    private fun hasActiveVpn(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private sealed interface ContextReturn {
        data class Single(val gameName: String, val options: List<String>, val selectedOption: Int = 0) : ContextReturn
        data class Bulk(val gamePaths: List<String>, val options: List<String>) : ContextReturn
    }
    private var pendingContextReturn: ContextReturn? = null

    private val gameContextOptions = listOf(MENU_MANAGE_COLLECTIONS, MENU_EMULATOR_OVERRIDE, MENU_RA_GAME_ID, MENU_RENAME, MENU_DELETE_GAME)

    override fun onUp(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu,
            is DialogState.BulkContextMenu,
            is DialogState.SaveSyncConflict,
            is DialogState.SaveSyncStaleBlock -> {
                ds.withMenuDelta(-1)?.let { nav.dialogState.value = it }
            }
            is DialogState.QuickMenu -> {
                val newIdx = (ds.selectedIndex - 1).mod(ds.rows.size)
                nav.dialogState.value = ds.copy(selectedIndex = newIdx)
            }
            is DialogState.RommActionsMenu -> {
                val size = dev.cannoli.scorza.ui.components.RommActionRow.visibleRows(ds.hasDownloads).size
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(size))
            }
            is DialogState.RommSettingsMenu -> {
                val size = dev.cannoli.scorza.ui.components.RommSettingsRow.entries.size
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(size))
            }
            is DialogState.RommAdvancedMenu -> {
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(dev.cannoli.scorza.ui.components.ROMM_ADVANCED_ROWS.size))
            }
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveSelection(ds.keyboard, Direction.UP))
            is DialogState.ColorPicker -> {
                val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                val newRow = if (ds.selectedRow <= 0) totalRows - 1 else ds.selectedRow - 1
                nav.dialogState.value = ds.copy(selectedRow = newRow)
            }
            is DialogState.HexColorInput -> {
                val rowSize = HEX_ROW_SIZE
                val curRow = ds.selectedIndex / rowSize
                val col = ds.selectedIndex % rowSize
                val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                val newRow = if (curRow <= 0) totalRows - 1 else curRow - 1
                val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                nav.dialogState.value = ds.copy(selectedIndex = newIdx)
            }
            is DialogState.RommDownloads -> {
                val size = rommDownloader.queue.state.value.size
                if (size > 0) {
                    val idx = (ds.selectedIndex - 1).mod(size)
                    nav.dialogState.value = ds.copy(selectedIndex = idx)
                }
            }
            is DialogState.RommArtResults -> {
                val size = artResultRowCount(ds)
                if (size > 0) nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(size))
            }
            is DialogState.RommPlatformToggle -> {
                if (ds.items.isNotEmpty()) nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(ds.items.size))
            }
            is DialogState.RommCollectionToggle -> {
                if (ds.items.isNotEmpty()) nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex - 1).mod(ds.items.size))
            }
            else -> {}
        }
        return true
    }

    override fun onDown(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu,
            is DialogState.BulkContextMenu,
            is DialogState.SaveSyncConflict,
            is DialogState.SaveSyncStaleBlock -> {
                ds.withMenuDelta(1)?.let { nav.dialogState.value = it }
            }
            is DialogState.QuickMenu -> {
                val newIdx = (ds.selectedIndex + 1).mod(ds.rows.size)
                nav.dialogState.value = ds.copy(selectedIndex = newIdx)
            }
            is DialogState.RommActionsMenu -> {
                val size = dev.cannoli.scorza.ui.components.RommActionRow.visibleRows(ds.hasDownloads).size
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(size))
            }
            is DialogState.RommSettingsMenu -> {
                val size = dev.cannoli.scorza.ui.components.RommSettingsRow.entries.size
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(size))
            }
            is DialogState.RommAdvancedMenu -> {
                nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(dev.cannoli.scorza.ui.components.ROMM_ADVANCED_ROWS.size))
            }
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveSelection(ds.keyboard, Direction.DOWN))
            is DialogState.ColorPicker -> {
                val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                val newRow = if (ds.selectedRow >= totalRows - 1) 0 else ds.selectedRow + 1
                nav.dialogState.value = ds.copy(selectedRow = newRow)
            }
            is DialogState.HexColorInput -> {
                val rowSize = HEX_ROW_SIZE
                val curRow = ds.selectedIndex / rowSize
                val col = ds.selectedIndex % rowSize
                val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                val newRow = if (curRow >= totalRows - 1) 0 else curRow + 1
                val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                nav.dialogState.value = ds.copy(selectedIndex = newIdx)
            }
            is DialogState.RommDownloads -> {
                val size = rommDownloader.queue.state.value.size
                if (size > 0) {
                    val idx = (ds.selectedIndex + 1).mod(size)
                    nav.dialogState.value = ds.copy(selectedIndex = idx)
                }
            }
            is DialogState.RommArtResults -> {
                val size = artResultRowCount(ds)
                if (size > 0) nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(size))
            }
            is DialogState.RommPlatformToggle -> {
                if (ds.items.isNotEmpty()) {
                    nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(ds.items.size))
                }
            }
            is DialogState.RommCollectionToggle -> {
                if (ds.items.isNotEmpty()) {
                    nav.dialogState.value = ds.copy(selectedIndex = (ds.selectedIndex + 1).mod(ds.items.size))
                }
            }
            else -> {}
        }
        return true
    }

    override fun onLeft(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.Kitchen -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex - 1 + ds.urls.size) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.QuickInfo -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex - 1 + ds.urls.size) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.RommSettingsMenu -> cycleRommSettings(ds, -1)
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveSelection(ds.keyboard, Direction.LEFT))
            is DialogState.ColorPicker -> {
                val newCol = if (ds.selectedCol <= 0) COLOR_GRID_COLS - 1 else ds.selectedCol - 1
                nav.dialogState.value = ds.copy(selectedCol = newCol)
            }
            is DialogState.HexColorInput -> {
                val rowSize = HEX_ROW_SIZE
                val curRow = ds.selectedIndex / rowSize
                val col = ds.selectedIndex % rowSize
                val newCol = if (col <= 0) rowSize - 1 else col - 1
                nav.dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
            }
            else -> {}
        }
        return true
    }

    override fun onRight(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.Kitchen -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex + 1) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.QuickInfo -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex + 1) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.RommSettingsMenu -> cycleRommSettings(ds, 1)
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveSelection(ds.keyboard, Direction.RIGHT))
            is DialogState.ColorPicker -> {
                val newCol = if (ds.selectedCol >= COLOR_GRID_COLS - 1) 0 else ds.selectedCol + 1
                nav.dialogState.value = ds.copy(selectedCol = newCol)
            }
            is DialogState.HexColorInput -> {
                val rowSize = HEX_ROW_SIZE
                val curRow = ds.selectedIndex / rowSize
                val col = ds.selectedIndex % rowSize
                val newCol = if (col >= rowSize - 1) 0 else col + 1
                nav.dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
            }
            else -> {}
        }
        return true
    }

    override fun onConfirm(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu -> onContextMenuConfirm(ds)
            is DialogState.BulkContextMenu -> onBulkContextMenuConfirm(ds)
            is DialogState.DeleteConfirm -> onDeleteConfirm(ds)
            is KeyboardHost -> when (val r = KeyboardController.press(ds.keyboard)) {
                is KeyboardPress.Update -> nav.dialogState.value = ds.withKeyboard(r.state)
                KeyboardPress.Confirm -> dispatchKeyboardConfirm(ds)
            }
            is DialogState.QuitConfirm -> {
                activityActions.finishAffinity()
            }
            is DialogState.ColorPicker -> {
                val idx = ds.selectedRow * COLOR_GRID_COLS + ds.selectedCol
                val preset = COLOR_PRESETS.getOrNull(idx)
                if (preset != null) {
                    val hex = "#%06X".format(preset.color and 0xFFFFFF)
                    settingsViewModel.setColor(ds.settingKey, hex)
                    val entries = settingsViewModel.getColorEntries()
                    updateColorListOnStack(ds.settingKey, entries)
                    nav.dialogState.value = DialogState.None
                }
            }
            is DialogState.HexColorInput -> {
                val key = HEX_KEYS.getOrNull(ds.selectedIndex) ?: ""
                when (key) {
                    "" -> {}
                    KEY_BACKSPACE -> {
                        if (ds.currentHex.isNotEmpty()) {
                            nav.dialogState.value = ds.copy(currentHex = ds.currentHex.dropLast(1))
                        }
                    }
                    KEY_ENTER -> {
                        if (ds.currentHex.length == 6) {
                            settingsViewModel.setColor(ds.settingKey, "#${ds.currentHex}")
                            val entries = settingsViewModel.getColorEntries()
                            updateColorListOnStack(ds.settingKey, entries)
                            nav.dialogState.value = DialogState.None
                        }
                    }
                    else -> {
                        if (ds.currentHex.length < 6) {
                            nav.dialogState.value = ds.copy(currentHex = ds.currentHex + key)
                        }
                    }
                }
            }
            is DialogState.MissingApp -> {
                val glState = gameListViewModel.state.value
                if (glState.platformTag == "tools" || glState.platformTag == "ports") {
                    val item = gameListViewModel.getSelectedItem()
                    if (item is ListItem.AppItem) {
                        nav.dialogState.value = DialogState.None
                        ioScope.launch {
                            appsRepository.delete(item.app.id)
                            gameListViewModel.reload()
                            launcherActions.rescanSystemList()
                        }
                    }
                }
            }
            is DialogState.DeleteCollectionConfirm -> {
                val glState = gameListViewModel.state.value
                val deletingFromParent = glState.isCollection && !glState.isCollectionsList
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                if (!deletingFromParent) gameListViewModel.saveCollectionsPosition()
                ioScope.launch {
                    collectionManager.delete(ds.collectionId)
                    if (deletingFromParent) {
                        gameListViewModel.reload()
                        launcherActions.rescanSystemList()
                    } else {
                        if (settings.contentMode == dev.cannoli.scorza.settings.ContentMode.COLLECTIONS) {
                            withContext(Dispatchers.Main) {
                                nav.screenStack.removeAt(nav.screenStack.lastIndex)
                                launcherActions.rescanSystemList()
                            }
                        } else {
                            val remaining = collectionManager.topLevel()
                            if (remaining.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    nav.screenStack.removeAt(nav.screenStack.lastIndex)
                                    launcherActions.rescanSystemList()
                                }
                            } else {
                                gameListViewModel.loadCollectionsList(restoreIndex = true)
                            }
                        }
                    }
                }
            }
            is DialogState.UpdateDownload -> {
                val info = updateManager.updateAvailable.value
                if (info != null) {
                    updateManager.clearError()
                    ioScope.launch { updateManager.downloadAndInstall(info) }
                }
            }
            is DialogState.RestartRequired -> {
                activityActions.restartApp()
            }
            is DialogState.IntentAuditResult -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.SystemFoldersRegenerated -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.PlatformResetConfirm -> onPlatformReset(ds)
            is DialogState.QuickMenu -> {
                when (ds.rows.getOrNull(ds.selectedIndex)) {
                    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ROMM -> {
                        nav.dialogState.value = DialogState.None
                        nav.push(dev.cannoli.scorza.navigation.LauncherScreen.RommPlatformList())
                    }
                    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN -> launcherActions.openKitchen(fromQuickMenu = true)
                    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.RESCAN -> {
                        nav.dialogState.value = DialogState.RescanProgress(
                            0f, context.getString(dev.cannoli.scorza.R.string.boot_preparing))
                        launcherActions.rescanSystemList(
                            scanDisk = true,
                            onProgress = { tag, current, total ->
                                nav.dialogState.value = DialogState.RescanProgress(
                                    current.toFloat() / total.coerceAtLeast(1), tag)
                            },
                            onComplete = { nav.dialogState.value = DialogState.None },
                        )
                    }
                    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO -> {
                        val urls = dev.cannoli.scorza.server.KitchenManager.getUrls(hasVpn = hasActiveVpn())
                        nav.dialogState.value = DialogState.QuickInfo(urls = urls, kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning)
                    }
                    null -> nav.dialogState.value = DialogState.None
                }
            }
            is DialogState.RommDownloads -> {
                val item = rommDownloader.queue.state.value.inDisplayOrder().getOrNull(ds.selectedIndex) ?: return true
                when (item.status) {
                    is DownloadStatus.Failed -> rommDownloader.retry(item.key)
                    DownloadStatus.Queued, is DownloadStatus.Downloading ->
                        nav.dialogState.value = DialogState.RommConfirm(
                            dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD,
                            downloadKey = item.key,
                        )
                    else -> {}
                }
            }
            is DialogState.RommArtResults -> {
                rommArtFetcher.dismissResults()
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommActionsMenu -> onRommActionsConfirm(ds)
            is DialogState.RommSettingsMenu -> onRommSettingsConfirm(ds)
            is DialogState.RommPlatformToggle -> {
                val item = ds.items.getOrNull(ds.selectedIndex) ?: return true
                val nowVisible = !item.visible
                val hidden = settings.hiddenRommPlatforms.toMutableSet()
                if (nowVisible) hidden.remove(item.tag) else hidden.add(item.tag)
                settings.hiddenRommPlatforms = hidden
                val newItems = ds.items.toMutableList()
                newItems[ds.selectedIndex] = item.copy(visible = nowVisible)
                nav.dialogState.value = ds.copy(items = newItems)
            }
            is DialogState.RommCollectionToggle -> {
                val item = ds.items.getOrNull(ds.selectedIndex) ?: return true
                val nowVisible = !item.visible
                when (item.group) {
                    dev.cannoli.scorza.romm.RommCollectionGroup.USER -> rommStore.showUserCollections = nowVisible
                    dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> rommStore.showVirtualCollections = nowVisible
                    dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> rommStore.showSmartCollections = nowVisible
                }
                val newItems = ds.items.toMutableList()
                newItems[ds.selectedIndex] = item.copy(visible = nowVisible)
                nav.dialogState.value = ds.copy(items = newItems)
                if (nowVisible) {
                    ioScope.launch { rommBrowseViewModel.refresh(); rommBrowseViewModel.loadCollections() }
                }
            }
            is DialogState.RommAdvancedMenu -> {
                when (ds.selectedIndex) {
                    0 -> {
                        nav.dialogState.value = DialogState.None
                        ioScope.launch { rommBrowseViewModel.refresh() }
                    }
                    1 -> nav.dialogState.value = DialogState.RommConfirm(dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE)
                    else -> {
                        val tags = romsRepository.knownPlatformTags()
                        nav.dialogState.value = DialogState.None
                        dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                        rommArtFetcher.start(tags)
                    }
                }
            }
            is DialogState.RommConfirm -> onRommConfirm(ds)
            is DialogState.RAPreloadResult -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RAPreloadProgress -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.SaveSyncConflict -> onSaveConflictConfirm(ds)
            is DialogState.SaveSyncStaleBlock -> onSaveStaleConfirm(ds)
            else -> {}
        }
        return true
    }

    private fun onSaveConflictConfirm(ds: DialogState.SaveSyncConflict) {
        val deviceId = saveSyncService.deviceIdOrNull() ?: run {
            nav.dialogState.value = DialogState.None
            launcherActions.proceedPendingLaunch()
            return
        }
        ioScope.launch {
            try {
                if (ds.selectedIndex == 0) saveSyncService.applyConflictKeepLocal(ds.conflict, deviceId)
                else saveSyncService.applyConflictUseServer(ds.conflict, deviceId)
            } catch (_: Throwable) {
                // apply failed (offline/IO): never strand the launch; proceed with the local save
            } finally {
                withContext(Dispatchers.Main) {
                    nav.dialogState.value = DialogState.None
                    launcherActions.proceedPendingLaunch()
                }
            }
        }
    }

    private fun onSaveStaleConfirm(ds: DialogState.SaveSyncStaleBlock) {
        nav.dialogState.value = DialogState.None
        if (ds.selectedIndex == 0) launcherActions.proceedPendingLaunch() else launcherActions.cancelPendingLaunch()
    }

    private fun onRommActionsConfirm(ds: DialogState.RommActionsMenu) {
        when (dev.cannoli.scorza.ui.components.RommActionRow.visibleRows(ds.hasDownloads).getOrNull(ds.selectedIndex)) {
            dev.cannoli.scorza.ui.components.RommActionRow.DOWNLOADS -> {
                nav.dialogState.value = DialogState.RommDownloads()
            }
            dev.cannoli.scorza.ui.components.RommActionRow.RETURN_TO_CANNOLI -> {
                nav.dialogState.value = DialogState.None
                rommDownloader.clearFinished()
                while (isRommScreen()) nav.pop()
            }
            else -> {}
        }
    }

    private fun onRommSettingsConfirm(ds: DialogState.RommSettingsMenu) {
        when (dev.cannoli.scorza.ui.components.RommSettingsRow.entries.getOrNull(ds.selectedIndex)) {
            dev.cannoli.scorza.ui.components.RommSettingsRow.SERVER_INFO -> {
                nav.dialogState.value = DialogState.RommConnected(
                    host = rommStore.host,
                    username = rommStore.username,
                    version = rommStore.serverVersion,
                )
            }
            dev.cannoli.scorza.ui.components.RommSettingsRow.ADVANCED -> {
                nav.dialogState.value = DialogState.RommAdvancedMenu()
            }
            dev.cannoli.scorza.ui.components.RommSettingsRow.PLATFORMS -> {
                val hidden = settings.hiddenRommPlatforms
                val items = rommBrowseViewModel.allPlatforms.value.map { p ->
                    dev.cannoli.scorza.ui.screens.RommPlatformToggleItem(
                        tag = p.cannoliTag,
                        displayName = p.displayName,
                        visible = p.cannoliTag !in hidden,
                    )
                }
                nav.dialogState.value = DialogState.RommPlatformToggle(items)
            }
            dev.cannoli.scorza.ui.components.RommSettingsRow.COLLECTIONS -> {
                val items = listOf(
                    dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.USER, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_user), rommStore.showUserCollections),
                    dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_virtual), rommStore.showVirtualCollections),
                    dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.SMART, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_smart), rommStore.showSmartCollections),
                )
                nav.dialogState.value = DialogState.RommCollectionToggle(items)
            }
            else -> {}
        }
    }

    private fun onRommConfirm(ds: DialogState.RommConfirm) {
        when (ds.action) {
            dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE -> {
                nav.dialogState.value = DialogState.None
                ioScope.launch { rommBrowseViewModel.rebuild() }
            }
            dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT -> {
                rommStore.disconnect()
                settingsViewModel.load()
                nav.dialogState.value = DialogState.None
                while (isRommScreen()) nav.pop()
            }
            dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD -> {
                ds.downloadKey?.let { rommDownloader.cancel(it) }
                nav.dialogState.value = DialogState.RommDownloads()
            }
            dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL -> {
                rommDownloader.cancelAll()
                nav.dialogState.value = DialogState.RommDownloads()
            }
        }
    }

    private fun artResultRowCount(ds: DialogState.RommArtResults): Int =
        dev.cannoli.scorza.ui.screens.rommArtIssueRows(
            ds.results,
            context.getString(dev.cannoli.ui.R.string.romm_art_section_no_match),
            context.getString(dev.cannoli.ui.R.string.romm_art_section_failed),
        ).size

    override fun onBack(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.backspace(ds.keyboard))
            is DialogState.ColorPicker -> {
                val entries = settingsViewModel.getColorEntries()
                updateColorListOnStack(ds.settingKey, entries)
                nav.dialogState.value = DialogState.None
            }
            is DialogState.HexColorInput -> {
                if (ds.currentHex.isNotEmpty()) {
                    nav.dialogState.value = ds.copy(currentHex = ds.currentHex.dropLast(1))
                }
            }
            is DialogState.ContextMenu, is DialogState.BulkContextMenu -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
            }
            is DialogState.QuickMenu -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.QuickInfo -> {
                val rows = dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.visibleRows(rommStore.isConfigured, dev.cannoli.scorza.server.KitchenManager.isRunning)
                nav.dialogState.value = DialogState.QuickMenu(
                    rows = rows,
                    kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
                    selectedIndex = rows.indexOf(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO).coerceAtLeast(0)
                )
            }
            is DialogState.DeleteConfirm,
            is DialogState.DeleteCollectionConfirm -> {
                restoreContextMenu()
            }
            is DialogState.QuitConfirm -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.CollectionCreated -> {
                refreshCollectionPickerOnStack()
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RenameResult -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.MissingCore,
            is DialogState.MissingApp,
            is DialogState.LaunchError -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.UpdateDownload -> {
                updateManager.cancelDownload()
                updateManager.clearError()
                nav.dialogState.value = DialogState.About()
            }
            is DialogState.About -> {
                nav.dialogState.value = DialogState.None
                launcherActions.rescanSystemList()
            }
            is DialogState.Kitchen -> {
                if (ds.fromQuickMenu) {
                    val rows = dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.visibleRows(rommStore.isConfigured, dev.cannoli.scorza.server.KitchenManager.isRunning)
                    nav.dialogState.value = DialogState.QuickMenu(
                        rows = rows,
                        kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
                        selectedIndex = rows.indexOf(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN).coerceAtLeast(0)
                    )
                } else {
                    nav.dialogState.value = DialogState.None
                }
                launcherActions.rescanSystemList()
            }
            is DialogState.RAAccount -> {
                nav.dialogState.value = DialogState.None
                // Only pop when the RA credential sub-list is active (post-login); when opened
                // directly from the Integrations list, dismissing must stay in Integrations.
                if (settingsViewModel.state.value.activeCategory == "retroachievements") settingsViewModel.exitSubList()
            }
            is DialogState.RALoggingIn -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommConnected -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommPairing -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RestartRequired -> {}
            is DialogState.IntentAuditResult -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.SystemFoldersRegenerated -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.PlatformResetConfirm -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommDownloads -> nav.dialogState.value = DialogState.None
            is DialogState.RommArtResults -> {
                rommArtFetcher.dismissResults()
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommPlatformToggle -> {
                nav.dialogState.value = DialogState.None
                ioScope.launch { rommBrowseViewModel.loadPlatforms() }
            }
            is DialogState.RommCollectionToggle -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RommActionsMenu -> nav.dialogState.value = DialogState.None
            is DialogState.RommSettingsMenu -> nav.dialogState.value = DialogState.None
            is DialogState.RommAdvancedMenu -> {
                nav.dialogState.value = DialogState.RommSettingsMenu(
                    concurrent = settings.concurrentDownloads,
                    artType = rommStore.artType,
                    selectedIndex = dev.cannoli.scorza.ui.components.RommSettingsRow.entries
                        .indexOf(dev.cannoli.scorza.ui.components.RommSettingsRow.ADVANCED),
                )
            }
            is DialogState.RommConfirm -> {
                when (ds.action) {
                    dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE ->
                        nav.dialogState.value = DialogState.RommAdvancedMenu()
                    dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT ->
                        nav.dialogState.value = DialogState.RommConnected(
                            host = rommStore.host,
                            username = rommStore.username,
                            version = rommStore.serverVersion,
                        )
                    dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD ->
                        nav.dialogState.value = DialogState.RommDownloads()
                    dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL ->
                        nav.dialogState.value = DialogState.RommDownloads()
                }
            }
            is DialogState.RAPreloadResult -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RAPreloadProgress -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.SaveSyncConflict -> {
                nav.dialogState.value = DialogState.None
                launcherActions.proceedPendingLaunch()
            }
            is DialogState.SaveSyncStaleBlock -> {
                nav.dialogState.value = DialogState.None
                launcherActions.cancelPendingLaunch()
            }
            is DialogState.SaveSyncChecking -> {}
            else -> {}
        }
        return true
    }

    override fun onStart(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> dispatchKeyboardConfirm(ds)
            is DialogState.HexColorInput -> {
                if (ds.currentHex.length == 6) {
                    settingsViewModel.setColor(ds.settingKey, "#${ds.currentHex}")
                    val entries = settingsViewModel.getColorEntries()
                    updateColorListOnStack(ds.settingKey, entries)
                    nav.dialogState.value = DialogState.None
                }
            }
            else -> {}
        }
        return true
    }

    override fun onNorth(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) {
            if (nav.currentScreen is LauncherScreen.RommPlatformList) {
                nav.dialogState.value = DialogState.RommSettingsMenu(
                    concurrent = settings.concurrentDownloads,
                    artType = rommStore.artType,
                )
                return true
            }
            return false
        }
        when (ds) {
            is KeyboardHost -> if (ds.keyboard.layout.supportsSpace) {
                nav.dialogState.value = ds.withKeyboard(KeyboardController.insertChar(ds.keyboard, " "))
            }
            is DialogState.About -> {
                nav.dialogState.value = DialogState.None
                nav.screenStack.add(LauncherScreen.Credits())
            }
            is DialogState.Kitchen -> {
                dev.cannoli.scorza.server.KitchenManager.stop(context)
                nav.dialogState.value = DialogState.None
                launcherActions.rescanSystemList()
            }
            is DialogState.RAAccount -> {
                settings.raUsername = ""
                settings.raToken = ""
                settings.raPassword = ""
                settingsViewModel.load()
                nav.dialogState.value = DialogState.None
            }
            is DialogState.ColorPicker -> {
                val currentHex = settingsViewModel.getColorHex(ds.settingKey).removePrefix("#")
                nav.dialogState.value = DialogState.HexColorInput(
                    settingKey = ds.settingKey,
                    title = ds.title,
                    currentHex = currentHex
                )
            }
            is DialogState.RommDownloads -> if (rommDownloader.queue.activeCount() >= 2) {
                nav.dialogState.value = DialogState.RommConfirm(dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL)
            }
            else -> {}
        }
        return true
    }

    override fun onWest(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.CollectionRenameInput -> {
                restoreContextMenu()
            }
            is DialogState.NewCollectionInput,
            is DialogState.NewFolderInput -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.HexColorInput -> {
                launcherActions.openColorPicker(ds.settingKey)
            }
            is DialogState.About -> {
                val info = updateManager.updateAvailable.value
                if (info != null) {
                    nav.dialogState.value = DialogState.UpdateDownload(info.versionName, info.changelog)
                    ioScope.launch { updateManager.downloadAndInstall(info) }
                }
            }
            is DialogState.RAAccount -> {
                nav.dialogState.value = DialogState.None
                val store = dev.cannoli.scorza.ra.RaOfflineStore(
                    dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).configRaOffline
                )
                val platforms = store.entries()
                    .groupBy { it.platformTag }
                    .map { (tag, list) -> LauncherScreen.RaOfflinePlatform(tag, platformResolver.getDisplayName(tag), list.size) }
                    .sortedBy { it.name.lowercase() }
                nav.screenStack.add(LauncherScreen.RetroAchievementsOfflinePlatforms(platforms = platforms))
                return true
            }
            is DialogState.RommConnected -> {
                nav.dialogState.value = DialogState.RommConfirm(dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT)
            }
            else -> {}
        }
        return true
    }

    override fun onSelect(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> {
                val layout = ds.keyboard.layout
                if ((layout.supportsCaps || layout.supportsSymbols) && !nav.selectDown) {
                    nav.selectDown = true
                    nav.selectHeld = false
                    selectHoldHandler.postDelayed(selectHoldRunnable, 400)
                }
            }
            else -> {}
        }
        return true
    }

    override fun onSelectUp(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        if (ds is KeyboardHost) {
            cancelSelectHold()
            if (!nav.selectHeld) {
                val ks = ds.keyboard
                if (ks.symbols) {
                    nav.dialogState.value = ds.withKeyboard(ks.copy(caps = nav.capsBeforeSymbols, symbols = false))
                } else if (ks.layout.supportsCaps) {
                    nav.dialogState.value = ds.withKeyboard(ks.copy(caps = !ks.caps))
                }
            }
            nav.selectDown = false
            nav.selectHeld = false
            return true
        }
        return false
    }

    override fun onL1(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveCursor(ds.keyboard, -1))
            else -> {}
        }
        return true
    }

    override fun onR1(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.moveCursor(ds.keyboard, 1))
            else -> {}
        }
        return true
    }

    override fun onL2(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.cursorToStart(ds.keyboard))
            else -> {}
        }
        return true
    }

    override fun onR2(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is KeyboardHost -> nav.dialogState.value = ds.withKeyboard(KeyboardController.cursorToEnd(ds.keyboard))
            else -> {}
        }
        return true
    }

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (nav.currentScreen == LauncherScreen.SystemList) {
            val fghItem = nav.pendingFghItem
            if (fghItem != null) {
                nav.pendingFghItem = null
                onFghContextMenuConfirm(fghItem, state)
            } else {
                when (state.options[state.selectedOption]) {
                    MENU_RENAME -> {
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = state.gameName,
                            keyboard = KeyboardState(text = state.gameName, cursorPos = state.gameName.length),
                        )
                    }
                    MENU_DOWNLOAD_ART -> {
                        val tag = systemListViewModel.getSelectedPlatformTag()
                        nav.dialogState.value = DialogState.None
                        if (tag != null) {
                            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                            rommArtFetcher.start(listOf(tag))
                        }
                    }
                }
            }
            return
        }
        val item = gameListViewModel.getSelectedItem() ?: return
        val glState = gameListViewModel.state.value
        val rom = (item as? ListItem.RomItem)?.rom
        val app = (item as? ListItem.AppItem)?.app
        val collection = when (item) {
            is ListItem.CollectionItem -> item.collection
            is ListItem.ChildCollectionItem -> item.collection
            else -> null
        }
        val displayName = when (item) {
            is ListItem.RomItem -> item.rom.displayName
            is ListItem.AppItem -> item.app.displayName
            is ListItem.SubfolderItem -> item.name
            is ListItem.CollectionItem -> item.collection.displayName
            is ListItem.ChildCollectionItem -> item.collection.displayName
        }
        pendingContextReturn = ContextReturn.Single(state.gameName, state.options, state.selectedOption)
        val selected = state.options[state.selectedOption]
        when {
            selected == MENU_REMOVE_FROM_RECENTS -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                ioScope.launch {
                    item.recentKey()?.let { clearRecentlyPlayedByPath(it) }
                    gameListViewModel.loadRecentlyPlayed()
                    launcherActions.rescanSystemList()
                }
                return
            }
            selected == MENU_RENAME -> {
                if (collection != null) {
                    nav.dialogState.value = DialogState.CollectionRenameInput(
                        collectionId = collection.id,
                        oldDisplayName = collection.displayName,
                        keyboard = KeyboardState(text = displayName),
                    )
                } else {
                    nav.dialogState.value = DialogState.RenameInput(
                        gameName = displayName,
                        keyboard = KeyboardState(text = displayName, cursorPos = displayName.length),
                    )
                }
            }
            selected == MENU_DELETE || selected == MENU_DELETE_GAME -> {
                if (collection != null) {
                    nav.dialogState.value = DialogState.DeleteCollectionConfirm(collectionId = collection.id, displayName = collection.displayName)
                } else {
                    nav.dialogState.value = DialogState.DeleteConfirm(gameName = displayName)
                }
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                val path = item.recentKey() ?: return
                openCollectionManager(listOf(path), displayName)
            }
            selected == MENU_CHILD_COLLECTIONS -> {
                if (collection != null) openChildPicker(collection.id)
            }
            selected == MENU_DELETE_ART -> {
                if (rom != null) {
                    pendingContextReturn = null
                    rom.artFile?.delete()
                    scanner.markLauncherMutation(rom.platformTag)
                    gameListViewModel.reload()
                    nav.dialogState.value = DialogState.None
                }
            }
            selected == MENU_RA_GAME_ID || selected.startsWith("$MENU_RA_GAME_ID\t") -> {
                if (rom != null) {
                    val current = rom.raGameId?.toString() ?: ""
                    nav.dialogState.value = DialogState.RenameInput(
                        gameName = "ra_game_id:${rom.path.absolutePath}",
                        keyboard = KeyboardState(text = current, cursorPos = current.length, layout = KeyboardLayout.Number),
                    )
                }
            }
            selected == MENU_PRELOAD_ACHIEVEMENTS || selected.startsWith("$MENU_PRELOAD_ACHIEVEMENTS\t") -> {
                if (rom != null) {
                    raPreloadController.preloadRom(rom)
                } else {
                    nav.dialogState.value = DialogState.None
                }
            }
            selected == MENU_REMOVE -> {
                if (app != null) {
                    pendingContextReturn = null
                    ioScope.launch {
                        appsRepository.delete(app.id)
                        gameListViewModel.reload()
                        launcherActions.rescanSystemList()
                    }
                    nav.dialogState.value = DialogState.None
                }
            }
            selected == MENU_ADD_FAVORITE || selected == MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                gameListViewModel.toggleFavorite { launcherActions.rescanSystemList() }
                nav.dialogState.value = DialogState.None
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                if (rom == null) return
                openEmulatorPicker(rom)
            }
            selected == MENU_SAVE_SLOTS -> {
                if (rom == null) return
                nav.dialogState.value = DialogState.None
                val gameKey = RomKeys.relativeKey(rom.path, romDir())
                val romId = saveSyncService.isSyncableGame(gameKey) ?: return
                val tag = rom.platformTag
                val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
                val emulator = RomKeys.coreDisplayNameFor(rom, platformResolver)
                ioScope.launch {
                    val slots = runCatching { slotManager.listSlots(gameKey, romId) }.onFailure { e ->
                        ErrorLog.write("save_slots_open: ${e.message}")
                    }.getOrDefault(emptyList())
                    withContext(Dispatchers.Main) {
                        nav.push(LauncherScreen.SaveSlots(gameKey, tag, base, romId, emulator, slots))
                    }
                }
            }
        }
    }

    private fun openEmulatorPicker(rom: dev.cannoli.scorza.model.Rom) {
        val tag = rom.platformTag
        val bundledCoresDir2 = LaunchManager.extractBundledCores(context)
        val options = platformResolver.getCorePickerOptions(tag, context.packageManager,
            installedRaCores = installedCoreService.configuredCores(), embeddedCoresDir = bundledCoresDir2,
            unresponsivePackages = installedCoreService.configuredUnresponsive())
        val platformCoreId = platformResolver.getCoreMapping(tag)
        val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
        val defaultLabel = if (platformCoreName.isNotEmpty()) context.getString(dev.cannoli.scorza.R.string.emulator_platform_setting_named, platformCoreName) else context.getString(dev.cannoli.scorza.R.string.emulator_platform_setting)
        val defaultOption = EmulatorPickerOption("", defaultLabel, "")
        val allOptions = listOf(defaultOption) + options
        val override = platformResolver.getGameOverride(rom.path.absolutePath)
        val selectedIdx = if (override?.appPackage != null) {
            allOptions.indexOfFirst { it.appPackage == override.appPackage }.coerceAtLeast(0)
        } else if (override != null) {
            allOptions.indexOfFirst { it.coreId == override.coreId && (it.runnerLabel == override.runner || override.runner == null) }
                .coerceAtLeast(0)
        } else {
            0
        }
        nav.dialogState.value = DialogState.None
        nav.screenStack.add(LauncherScreen.EmulatorPicker(
            tag = tag,
            platformName = rom.displayName,
            cores = allOptions,
            selectedIndex = selectedIdx,
            gamePath = rom.path.absolutePath,
            activeIndex = selectedIdx
        ))
    }

    private fun onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
        pendingContextReturn = ContextReturn.Bulk(state.gamePaths, state.options)
        when (state.options[state.selectedOption]) {
            MENU_REMOVE_FROM_RECENTS -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                ioScope.launch {
                    state.gamePaths.forEach { path -> clearRecentlyPlayedByPath(path) }
                    gameListViewModel.loadRecentlyPlayed()
                    launcherActions.rescanSystemList()
                }
                return
            }
            MENU_ADD_FAVORITE -> {
                pendingContextReturn = null
                val favoritesId = collectionManager.favoritesId()
                ioScope.launch {
                    if (favoritesId != null) {
                        state.gamePaths.forEach { path -> addPathToCollection(favoritesId, path) }
                    }
                    gameListViewModel.reload()
                    launcherActions.rescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                val favoritesId = collectionManager.favoritesId()
                ioScope.launch {
                    if (favoritesId != null) {
                        state.gamePaths.forEach { path -> removePathFromCollection(favoritesId, path) }
                    }
                    gameListViewModel.reload()
                    launcherActions.rescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(state.gamePaths, context.getString(dev.cannoli.scorza.R.string.bulk_selected, state.gamePaths.size))
            }
            MENU_DELETE_GAME -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.DeleteConfirm(
                    gameName = context.resources.getQuantityString(dev.cannoli.scorza.R.plurals.bulk_delete_count, state.gamePaths.size, state.gamePaths.size),
                    bulkPaths = state.gamePaths
                )
            }
            MENU_DELETE_ART -> {
                pendingContextReturn = null
                val pathSet = state.gamePaths.toSet()
                gameListViewModel.state.value.items
                    .filterIsInstance<ListItem.RomItem>()
                    .filter { it.rom.path.absolutePath in pathSet }
                    .forEach { romItem ->
                        romItem.rom.artFile?.delete()
                        scanner.markLauncherMutation(romItem.rom.platformTag)
                    }
                gameListViewModel.reload()
                nav.dialogState.value = DialogState.None
            }
            MENU_PRELOAD_ACHIEVEMENTS -> {
                pendingContextReturn = null
                val pathSet = state.gamePaths.toSet()
                val roms = gameListViewModel.state.value.items
                    .filterIsInstance<ListItem.RomItem>()
                    .map { it.rom }
                    .filter { rom ->
                        rom.path.absolutePath in pathSet &&
                            dev.cannoli.scorza.ra.RaPreloadEligibility.isEligible(
                                platformTag = rom.platformTag,
                                embeddedCorePresent = launchManager.getEmbeddedCorePath(rom) != null,
                                raLoggedIn = settings.raToken.isNotEmpty(),
                            )
                    }
                raPreloadController.preloadBulk(roms)
            }
            MENU_REMOVE -> {
                pendingContextReturn = null
                val pathSet = state.gamePaths.toSet()
                ioScope.launch {
                    gameListViewModel.state.value.items.forEach { item ->
                        if (item is ListItem.AppItem && item.recentKey() in pathSet) {
                            appsRepository.delete(item.app.id)
                        }
                    }
                    gameListViewModel.reload()
                    launcherActions.rescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            MENU_REMOVE_FROM_COLLECTION -> {
                pendingContextReturn = null
                val glState = gameListViewModel.state.value
                val collectionId = glState.collectionId ?: return
                val pathSet = state.gamePaths.toSet()
                ioScope.launch {
                    glState.items.forEach { item ->
                        if (item.recentKey() !in pathSet) return@forEach
                        val ref = when (item) {
                            is ListItem.RomItem -> dev.cannoli.scorza.db.LibraryRef.Rom(item.rom.id)
                            is ListItem.AppItem -> dev.cannoli.scorza.db.LibraryRef.App(item.app.id)
                            else -> null
                        }
                        if (ref != null) collectionManager.removeMember(collectionId, ref)
                    }
                    gameListViewModel.reload()
                    launcherActions.rescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
        }
    }

    private fun onDeleteConfirm(state: DialogState.DeleteConfirm) {
        pendingContextReturn = null
        if (state.bulkPaths != null) {
            val pathSet = state.bulkPaths.toSet()
            val toDelete = gameListViewModel.state.value.items
                .filterIsInstance<ListItem.RomItem>()
                .filter { it.rom.path.absolutePath in pathSet }
                .map { it.rom }
            ioScope.launch {
                toDelete.forEach { deleteRom(it) }
                gameListViewModel.reload()
                launcherActions.rescanSystemList()
                withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
            }
        } else {
            val item = gameListViewModel.getSelectedItem()
                ?: (systemListViewModel.getSelectedItem() as? SystemListViewModel.ListItem.GameItem)?.item
            if (item is ListItem.SubfolderItem) {
                val tag = gameListViewModel.state.value.platformTag
                val dir = File(romDir(), "$tag${File.separator}${item.path}")
                val prefix = relativeRomPath(dir)
                if (prefix == null) {
                    nav.dialogState.value = DialogState.None
                    return
                }
                ioScope.launch {
                    dir.deleteRecursively()
                    romsRepository.deleteRomsUnderPrefix(tag, prefix)
                    scanner.markLauncherMutation(tag)
                    gameListViewModel.reload()
                    launcherActions.rescanSystemList()
                    withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
                }
                return
            }
            val rom = (item as? ListItem.RomItem)?.rom ?: return
            ioScope.launch {
                deleteRom(rom)
                gameListViewModel.reload()
                launcherActions.rescanSystemList()
                withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
            }
        }
    }

    fun onCollectionPickerConfirm(state: LauncherScreen.CollectionPicker) {
        val added = state.checkedIndices - state.initialChecked
        val removed = state.initialChecked - state.checkedIndices
        val toAdd = added.mapNotNull { state.collectionIds.getOrNull(it) }
        val toRemove = removed.mapNotNull { state.collectionIds.getOrNull(it) }
        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            ioScope.launch {
                for (path in state.gamePaths) {
                    toAdd.forEach { id -> addPathToCollection(id, path) }
                    toRemove.forEach { id -> removePathFromCollection(id, path) }
                }
                gameListViewModel.reload()
                launcherActions.rescanSystemList()
            }
        }
        nav.screenStack.removeAt(nav.screenStack.lastIndex)
        restoreContextMenu()
    }

    fun onChildPickerConfirm(screen: LauncherScreen.ChildPicker) {
        val parentId = screen.parentId
        if (collectionManager.byId(parentId) == null) {
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            restoreContextMenu()
            return
        }
        val targetChildIds = screen.checkedIndices.mapNotNull { screen.collectionIds.getOrNull(it) }.toSet()
        val currentChildIds = collectionManager.children(parentId).map { it.id }.toSet()
        ioScope.launch {
            (targetChildIds - currentChildIds).forEach { collectionManager.setParent(it, parentId) }
            (currentChildIds - targetChildIds).forEach { collectionManager.setParent(it, null) }
            gameListViewModel.reload()
            launcherActions.rescanSystemList()
        }
        nav.screenStack.removeAt(nav.screenStack.lastIndex)
        restoreContextMenu()
    }

    fun onEmulatorPickerConfirm(screen: LauncherScreen.EmulatorPicker) {
        val chosen = screen.cores.getOrNull(screen.selectedIndex) ?: return
        if (screen.gamePath != null) {
            if (chosen.coreId.isEmpty() && chosen.appPackage == null) {
                platformResolver.setGameOverride(screen.gamePath, null, null)
                platformResolver.setGameAppOverride(screen.gamePath, null)
            } else if (chosen.appPackage != null) {
                platformResolver.setGameAppOverride(screen.gamePath, chosen.appPackage)
            } else {
                platformResolver.setGameOverride(screen.gamePath, chosen.coreId, chosen.runnerLabel)
            }
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            restoreContextMenu()
        } else {
            if (chosen.appPackage != null) {
                platformResolver.setAppMapping(screen.tag, chosen.appPackage)
            } else {
                platformResolver.setCoreMapping(screen.tag, chosen.coreId, chosen.runnerLabel)
            }
            platformResolver.saveCoreMappings()
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            val cm = nav.screenStack.lastOrNull()
            if (cm is LauncherScreen.EmulatorMapping) {
                val all = emulatorMappingBuilder.detailedMappings()
                val filtered = emulatorMappingBuilder.filter(all, cm.filter)
                val idx = filtered.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
            }
        }
    }

    private fun onPlatformReset(state: DialogState.PlatformResetConfirm) {
        platformResolver.resetPlatformMapping(state.tag)
        nav.dialogState.value = DialogState.None
        val mapping = nav.screenStack.lastOrNull() as? LauncherScreen.PlatformMapping ?: return
        nav.screenStack[nav.screenStack.lastIndex] = emulatorMappingBuilder.buildPlatformMapping(
            mapping.tag, mapping.platformName, showAll = mapping.showAll,
            selectedIndex = mapping.selectedIndex, scrollTarget = mapping.scrollTarget,
        )
        val mappingIdx = nav.screenStack.indexOfLast { it is LauncherScreen.EmulatorMapping }
        if (mappingIdx >= 0) {
            val cm = nav.screenStack[mappingIdx] as LauncherScreen.EmulatorMapping
            val all = emulatorMappingBuilder.detailedMappings()
            val filtered = emulatorMappingBuilder.filter(all, cm.filter)
            nav.screenStack[mappingIdx] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = cm.selectedIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)))
        }
    }

    fun buildGameContextOptions(item: ListItem, glState: GameListViewModel.State): List<String> {
        if (glState.isCollectionsList || item is ListItem.ChildCollectionItem) return listOf(MENU_RENAME, MENU_CHILD_COLLECTIONS, MENU_DELETE)
        if (item is ListItem.SubfolderItem) return listOf(MENU_RENAME, MENU_DELETE)
        val rom = (item as? ListItem.RomItem)?.rom
        val app = (item as? ListItem.AppItem)?.app
        val isApk = app != null
        val platformTag = rom?.platformTag ?: (if (app?.type == AppType.TOOL) "tools" else "ports")
        val romPath = rom?.path?.absolutePath
        val isFav = when {
            rom != null -> rom.id in glState.favoriteRomIds
            app != null -> app.id in glState.favoriteAppIds
            else -> false
        } || (glState.isCollection && glState.isFavorites)
        return buildList {
            if (glState.platformTag == "recently_played") add(MENU_REMOVE_FROM_RECENTS)
            add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
            if (isApk) {
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_RENAME)
                add(MENU_REMOVE)
            } else {
                addAll(gameContextOptions.map { menuItem ->
                    when {
                        menuItem == MENU_EMULATOR_OVERRIDE && romPath != null -> {
                        val bundledCoresDir = LaunchManager.extractBundledCores(context)
                        val options = platformResolver.getCorePickerOptions(platformTag, context.packageManager,
                            installedRaCores = installedCoreService.configuredCores(), embeddedCoresDir = bundledCoresDir,
                            unresponsivePackages = installedCoreService.configuredUnresponsive())
                        val override = platformResolver.getGameOverride(romPath)
                        if (override != null) {
                            val match = if (override.appPackage != null) {
                                options.firstOrNull { it.appPackage == override.appPackage }
                            } else {
                                options.firstOrNull { it.coreId == override.coreId && (override.runner == null || it.runnerLabel == override.runner) }
                            }
                            if (match != null) {
                                val desc = if (match.appPackage != null) match.displayName
                                    else "${match.runnerLabel} (${match.displayName})"
                                "$MENU_EMULATOR_OVERRIDE\t$desc"
                            } else menuItem
                        } else {
                            "$MENU_EMULATOR_OVERRIDE\tPlatform Default"
                        }
                        }
                        menuItem == MENU_RA_GAME_ID -> "$MENU_RA_GAME_ID\t${rom?.raGameId?.toString() ?: "Autodetect"}"
                        else -> menuItem
                    }
                })
                if (rom?.artFile != null) {
                    val idx = indexOf(MENU_DELETE_GAME)
                    if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
                }
                if (rom != null && dev.cannoli.scorza.ra.RaPreloadEligibility.isEligible(
                        platformTag = rom.platformTag,
                        embeddedCorePresent = launchManager.getEmbeddedCorePath(rom) != null,
                        raLoggedIn = settings.raToken.isNotEmpty(),
                    )
                ) {
                    val cached = rom.raCachedGameId?.let { gid ->
                        dev.cannoli.scorza.ra.RaOfflineStore(
                            dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).configRaOffline
                        ).isCached(gid)
                    } ?: false
                    val item = if (cached) "$MENU_PRELOAD_ACHIEVEMENTS\tCached" else MENU_PRELOAD_ACHIEVEMENTS
                    val raIdx = indexOfFirst { it == MENU_RA_GAME_ID || it.startsWith("$MENU_RA_GAME_ID\t") }
                    if (raIdx >= 0) add(raIdx + 1, item) else add(item)
                }
                if (item is ListItem.RomItem) {
                    if (saveSyncService.isSyncableGame(RomKeys.relativeKey(item.rom.path, romDir())) != null) {
                        add(MENU_SAVE_SLOTS)
                    }
                }
            }
        }
    }

    fun openCollectionManager(gamePaths: List<String>, title: String) {
        val all = collectionManager.all().filter { it.type == CollectionType.STANDARD }
        val ids = all.map { it.id }
        val displayNames = all.map { it.displayName }
        val alreadyIn = collectionsContainingPaths(gamePaths, all)
        val initialChecked = ids.indices
            .filter { ids[it] in alreadyIn }
            .toSet()
        nav.dialogState.value = DialogState.None
        nav.screenStack.add(LauncherScreen.CollectionPicker(
            gamePaths = gamePaths,
            title = title,
            collectionIds = ids,
            displayNames = displayNames,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    fun openChildPicker(parentId: Long) {
        val parent = collectionManager.byId(parentId) ?: return
        val all = collectionManager.all().filter { it.type == CollectionType.STANDARD }
        val ancestorIds = collectionManager.ancestors(parent.id).map { it.id }.toSet() + parent.id
        val available = all.filter { it.id !in ancestorIds }
        val availableIds = available.map { it.id }
        val displayNames = available.map { it.displayName }
        val currentChildIds = collectionManager.children(parent.id).map { it.id }.toSet()
        val initialChecked = available.indices
            .filter { available[it].id in currentChildIds }
            .toSet()
        nav.dialogState.value = DialogState.None
        nav.screenStack.add(LauncherScreen.ChildPicker(
            parentId = parent.id,
            collectionIds = availableIds,
            displayNames = displayNames,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    fun restoreContextMenu() {
        when (val ret = pendingContextReturn) {
            is ContextReturn.Single -> {
                val item = gameListViewModel.getSelectedItem()
                if (item != null) {
                    val glState = gameListViewModel.state.value
                    val newOptions = buildGameContextOptions(item, glState)
                    val oldSelected = ret.options.getOrNull(ret.selectedOption)
                    val restoredIdx = if (oldSelected != null) {
                        val key = oldSelected.substringBefore('\t')
                        newOptions.indexOfFirst { it.startsWith(key) }.coerceAtLeast(0)
                    } else 0
                    nav.dialogState.value = DialogState.ContextMenu(
                        gameName = ret.gameName,
                        selectedOption = restoredIdx,
                        options = newOptions
                    )
                } else {
                    pendingContextReturn = null
                    nav.dialogState.value = DialogState.None
                }
            }
            is ContextReturn.Bulk -> {
                nav.dialogState.value = DialogState.BulkContextMenu(
                    gamePaths = ret.gamePaths,
                    options = ret.options
                )
            }
            null -> nav.dialogState.value = DialogState.None
        }
    }

    private fun dispatchKeyboardConfirm(ds: KeyboardHost) {
        when (ds) {
            is DialogState.RenameInput -> onRenameConfirm(ds)
            is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
            is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
            is DialogState.NewFolderInput -> onNewFolderConfirm(ds)
            else -> {}
        }
    }

    private fun onNewCollectionConfirm(state: DialogState.NewCollectionInput) {
        val name = state.currentName.trim()
        if (name.isEmpty()) {
            nav.dialogState.value = DialogState.None
            return
        }
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            val newId = collectionManager.create(name)
            if (state.parentId != null) {
                collectionManager.setParent(newId, state.parentId)
            }
            state.gamePaths.forEach { path ->
                resolvePathToRef(path)?.let { collectionManager.addMember(newId, it) }
            }
            gameListViewModel.reload()
            launcherActions.rescanSystemList()
            withContext(Dispatchers.Main) { refreshCollectionPickerOnStack() }
        }
    }

    private fun onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.oldDisplayName) {
            restoreContextMenu()
            return
        }
        val glState = gameListViewModel.state.value
        val renamingFromParent = glState.isCollection && !glState.isCollectionsList
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            collectionManager.rename(state.collectionId, newName)
            if (renamingFromParent) {
                gameListViewModel.reload()
            } else {
                gameListViewModel.loadCollectionsList(restoreIndex = true)
            }
        }
    }

    private fun onNewFolderConfirm(state: DialogState.NewFolderInput) {
        val name = state.currentName.trim()
        if (name.isBlank()) {
            nav.dialogState.value = DialogState.None
            return
        }
        val newDir = File(state.parentPath, name)
        newDir.mkdirs()
        nav.dialogState.value = DialogState.None
        val screen = nav.currentScreen
        if (screen is LauncherScreen.DirectoryBrowser && screen.currentPath == state.parentPath) {
            val entries = screen.entries.toMutableList()
            if (name !in entries) {
                entries.add(name)
                entries.sort()
            }
            nav.screenStack[nav.screenStack.lastIndex] = screen.copy(entries = entries)
        }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        if (state.gameName.startsWith(dev.cannoli.scorza.input.screen.ControllerDetailInputHandler.RENAME_KEY_PREFIX)) {
            val mappingId = state.gameName.removePrefix(dev.cannoli.scorza.input.screen.ControllerDetailInputHandler.RENAME_KEY_PREFIX)
            val newName = state.currentName.trim()
            val vm = controllersViewModel
            val mapping = vm.state.value.connected.firstOrNull { it.mapping.id == mappingId }?.mapping
                ?: vm.state.value.savedMappings.firstOrNull { it.id == mappingId }
            if (mapping != null && newName.isNotEmpty() && newName != mapping.displayName) {
                vm.renameMapping(mapping, newName)
            }
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "ra_username") {
            settings.raUsername = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "ra_password") {
            settingsViewModel.raPassword = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "ra_login") {
            activityActions.startRaLogin(settings.raUsername, settingsViewModel.raPassword)
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_host") {
            rommStore.host = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_pair_code") {
            settingsViewModel.rommPairCode = dev.cannoli.scorza.romm.RommPairingCode.normalize(state.currentName)
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_pair") {
            activityActions.startRommPairing(rommStore.host, settingsViewModel.rommPairCode)
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_device_name") {
            val name = state.currentName.trim().ifEmpty { deviceRegistrar.defaultDeviceName() }
            nav.dialogState.value = DialogState.None
            ioScope.launch {
                runCatching { deviceRegistrar.register(name) }
                    .onSuccess {
                        settings.rommSaveSyncEnabled = true
                        withContext(Dispatchers.Main) { settingsViewModel.refreshSubList() }
                    }
                    .onFailure { ErrorLog.write("romm device registration failed: ${it.message}") }
            }
            return
        }
        if (state.gameName == "romm_search") {
            (nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.RommGameList)?.let {
                nav.replaceTop(it.copy(search = state.currentName.trim(), selectedIndex = 0, scrollTarget = 0))
            }
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_collection_search") {
            (nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.RommCollectionGameList)?.let {
                nav.replaceTop(it.copy(search = state.currentName.trim(), selectedIndex = 0, scrollTarget = 0))
            }
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "romm_global_search") {
            val term = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (term.isNotBlank()) nav.push(LauncherScreen.RommGlobalSearch(term = term))
            return
        }
        if (state.gameName == "launcher_search") {
            val term = state.currentName.trim()
            if (term.isBlank()) gameListViewModel.clearSearch() else gameListViewModel.setSearch(term)
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "save_slot_create") {
            val name = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (name.isNotBlank()) {
                val s = nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.SaveSlots ?: return
                ioScope.launch {
                    runCatching { slotManager.create(s.gameKey, s.tag, s.base, s.romId, s.emulator, name) }
                        .onFailure { ErrorLog.write("save slot create failed: ${it.message}") }
                    withContext(Dispatchers.Main) { saveSlotsHandler.refreshSlots() }
                }
            }
            return
        }
        if (state.gameName.startsWith("save_slot_rename:")) {
            val oldSlot = state.gameName.removePrefix("save_slot_rename:")
            val newSlot = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (newSlot.isNotBlank() && newSlot != oldSlot) {
                val s = nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.SaveSlots ?: return
                ioScope.launch {
                    runCatching { slotManager.rename(s.gameKey, s.tag, s.base, s.romId, s.emulator, oldSlot, newSlot) }
                        .onFailure { ErrorLog.write("save slot rename failed: ${it.message}") }
                    withContext(Dispatchers.Main) { saveSlotsHandler.refreshSlots() }
                }
            }
            return
        }
        if (state.gameName == "launcher_global_search") {
            val term = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (term.isNotBlank()) {
                nav.navigating = true
                gameListViewModel.loadGlobalSearch(dev.cannoli.scorza.model.GameSearchQuery(term)) {
                    launcherActions.scanResumableGames()
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            return
        }
        if (state.gameName == "title") {
            settings.title = state.currentName.trim()
            settingsViewModel.refreshSubList()
            settingsViewModel.load()
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.gameName.startsWith("ra_game_id:")) {
            val romPath = state.gameName.removePrefix("ra_game_id:")
            val gameId = state.currentName.trim().toIntOrNull()
            ioScope.launch {
                romsRepository.gameByPath(romPath)?.let { romsRepository.setRaGameId(it.id, gameId) }
                gameListViewModel.reload()
            }
            restoreContextMenu()
            return
        }
        if (nav.currentScreen == LauncherScreen.SystemList) {
            launcherActions.handleSystemListRename(state)
            return
        }
        val item = gameListViewModel.getSelectedItem() ?: return
        val newName = state.currentName.trim()
        val currentName = when (item) {
            is ListItem.RomItem -> item.rom.displayName
            is ListItem.SubfolderItem -> item.name
            is ListItem.AppItem -> item.app.displayName
            else -> return
        }
        if (newName.isEmpty() || newName == currentName) {
            pendingContextReturn = null
            nav.dialogState.value = DialogState.None
            return
        }

        pendingContextReturn = null
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            if (item is ListItem.AppItem) {
                appsRepository.updateDisplayName(item.app.id, newName)
                gameListViewModel.reload()
                launcherActions.rescanSystemList()
                return@launch
            }
            if (item is ListItem.SubfolderItem) {
                val tag = gameListViewModel.state.value.platformTag
                val oldDir = File(romDir(), "$tag${File.separator}${item.path}")
                val newDir = File(oldDir.parentFile, newName)
                val oldPrefix = relativeRomPath(oldDir)
                val ok = oldDir.renameTo(newDir)
                if (ok) {
                    val newPrefix = relativeRomPath(newDir)
                    if (oldPrefix != null && newPrefix != null) {
                        romsRepository.updateRomPathsUnderPrefix(tag, oldPrefix, newPrefix)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.RenameResult(false, context.getString(dev.cannoli.scorza.R.string.rename_error_directory))
                    }
                }
                scanner.markLauncherMutation(tag)
                gameListViewModel.reload()
                return@launch
            }
            val rom = (item as? ListItem.RomItem)?.rom ?: return@launch
            run {
                val result = atomicRename.rename(rom.path, newName, rom.platformTag)
                if (result.success) {
                    val newRomFile = File(rom.path.parentFile, "$newName.${rom.path.extension}")
                    val newRelative = relativeRomPath(newRomFile)
                    if (newRelative != null) {
                        romsRepository.renameRom(rom.id, newRelative, newName)
                    }
                } else {
                    val msg = when (result.error) {
                        AtomicRename.RenameError.CANNOT_RESOLVE_DIR -> context.getString(dev.cannoli.scorza.R.string.rename_cannot_resolve_dir)
                        AtomicRename.RenameError.ALREADY_EXISTS -> context.getString(dev.cannoli.scorza.R.string.rename_already_exists)
                        AtomicRename.RenameError.BACKUP_FAILED -> context.getString(dev.cannoli.scorza.R.string.rename_backup_failed)
                        AtomicRename.RenameError.RELOCATE_FAILED -> context.getString(dev.cannoli.scorza.R.string.rename_relocate_failed)
                        AtomicRename.RenameError.RENAME_FAILED, null -> context.getString(dev.cannoli.scorza.R.string.rename_error_generic)
                    }
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.RenameResult(false, msg)
                    }
                }
            }
            scanner.markLauncherMutation(rom.platformTag)
            gameListViewModel.reload()
        }
    }

    private fun onFghContextMenuConfirm(item: ListItem, state: DialogState.ContextMenu) {
        val selected = state.options[state.selectedOption]
        val path = item.recentKey() ?: return
        val displayName = when (item) {
            is ListItem.RomItem -> item.rom.displayName
            is ListItem.AppItem -> item.app.displayName
            else -> return
        }
        val rom = (item as? ListItem.RomItem)?.rom
        when {
            selected == MENU_ADD_FAVORITE || selected == MENU_REMOVE_FAVORITE -> {
                ioScope.launch {
                    val ref = resolvePathToRef(path) ?: return@launch
                    val favId = collectionManager.favoritesId() ?: return@launch
                    if (collectionManager.isMember(favId, ref)) collectionManager.removeMember(favId, ref)
                    else collectionManager.addMember(favId, ref)
                    launcherActions.rescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(listOf(path), displayName)
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                if (rom == null) return
                openEmulatorPicker(rom)
            }
            selected == MENU_DELETE || selected == MENU_DELETE_GAME -> {
                nav.dialogState.value = DialogState.DeleteConfirm(gameName = displayName)
            }
        }
    }

    private fun updateColorListOnStack(settingKey: String, entries: List<ColorEntry>) {
        val cl = nav.currentScreen
        if (cl is LauncherScreen.ColorList) {
            nav.screenStack[nav.screenStack.lastIndex] = cl.copy(
                colors = entries,
                selectedIndex = entries.indexOfFirst { it.key == settingKey }.coerceAtLeast(0)
            )
        }
    }

    private fun refreshCollectionPickerOnStack() {
        val cp = nav.currentScreen
        if (cp is LauncherScreen.CollectionPicker) {
            val all = collectionManager.all().filter { it.type == CollectionType.STANDARD }
            val ids = all.map { it.id }
            val displayNames = all.map { it.displayName }
            val alreadyIn = collectionsContainingPaths(cp.gamePaths, all)
            val newInitialChecked = ids.indices
                .filter { ids[it] in alreadyIn }
                .toSet()
            val oldCheckedIds = cp.checkedIndices.mapNotNull { cp.collectionIds.getOrNull(it) }.toSet()
            val newCheckedIndices = ids.indices
                .filter { ids[it] in oldCheckedIds || ids[it] in alreadyIn }
                .toSet()
            nav.screenStack[nav.screenStack.lastIndex] = cp.copy(
                collectionIds = ids,
                displayNames = displayNames,
                checkedIndices = newCheckedIndices,
                initialChecked = newInitialChecked
            )
        }
    }

    private fun collectionsContainingPaths(gamePaths: List<String>, candidates: List<CollectionsRepository.CollectionRow>): Set<Long> {
        if (gamePaths.isEmpty()) return emptySet()
        val sets = gamePaths.map { path ->
            val ref = resolvePathToRef(path) ?: return@map emptySet<Long>()
            val ids = collectionManager.collectionsContaining(ref)
            candidates.asSequence().map { it.id }.filter { it in ids }.toSet()
        }
        return if (gamePaths.size == 1) sets.first()
        else sets.reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
    }

    private fun addPathToCollection(collectionId: Long, path: String) {
        val ref = resolvePathToRef(path) ?: return
        collectionManager.addMember(collectionId, ref)
    }

    private fun removePathFromCollection(collectionId: Long, path: String) {
        val ref = resolvePathToRef(path) ?: return
        collectionManager.removeMember(collectionId, ref)
    }

    private fun resolvePathToRef(path: String): dev.cannoli.scorza.db.LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { dev.cannoli.scorza.db.LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { dev.cannoli.scorza.db.LibraryRef.Rom(it.id) }
        }
    }

    private suspend fun clearRecentlyPlayedByPath(path: String) {
        resolvePathToRef(path)?.let { recentlyPlayedManager.clear(it) }
    }

    private fun deleteRom(rom: dev.cannoli.scorza.model.Rom) {
        deleteRomFiles(rom)
        romsRepository.deleteRom(rom.id)
        scanner.markLauncherMutation(rom.platformTag)
    }

    private fun deleteRomFiles(rom: dev.cannoli.scorza.model.Rom) {
        val romFile = rom.path
        when {
            // Organizer-created bundle: subfolder is dedicated to this m3u (folder name == m3u stem).
            romFile.extension.equals("m3u", ignoreCase = true) &&
                romFile.parentFile?.name == romFile.nameWithoutExtension -> {
                romFile.parentFile?.deleteRecursively()
            }
            // Organizer-created single-disc cue bundle: subfolder is dedicated to this cue (folder name == cue stem).
            romFile.extension.equals("cue", ignoreCase = true) &&
                romFile.parentFile?.name == romFile.nameWithoutExtension -> {
                romFile.parentFile?.deleteRecursively()
            }
            // User-authored m3u sitting alongside discs: delete each line and the m3u itself.
            romFile.extension.equals("m3u", ignoreCase = true) -> {
                val parent = romFile.parentFile
                if (parent != null) {
                    try {
                        romFile.useLines { lines ->
                            for (line in lines) {
                                val trimmed = line.trim()
                                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                                File(parent, trimmed).takeIf { it.exists() && !it.isDirectory }?.delete()
                            }
                        }
                    } catch (_: Throwable) { }
                }
                romFile.delete()
            }
            else -> romFile.delete()
        }
    }

    private fun romDir(): File =
        settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(File(settings.sdCardRoot), "Roms")

    private fun relativeRomPath(file: File): String? {
        val romDir = romDir()
        return try {
            val relative = file.relativeTo(romDir).path
            if (relative.startsWith("..")) null else relative
        } catch (_: IllegalArgumentException) {
            null
        }
    }

}
