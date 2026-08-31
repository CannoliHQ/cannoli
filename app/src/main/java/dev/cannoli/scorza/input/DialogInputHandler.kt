package dev.cannoli.scorza.input

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
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.ListDialog
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.withMenuDelta
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.scorza.util.RomDirectoryWalker
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.components.Direction
import dev.cannoli.ui.components.HEX_KEYS
import dev.cannoli.ui.components.HEX_ROW_SIZE
import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.theme.COLOR_PRESETS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@ActivityScoped
class DialogInputHandler @Inject constructor(
    internal val nav: NavigationController,
    @IoScope internal val ioScope: CoroutineScope,
    @ActivityContext internal val context: android.content.Context,
    internal val settings: SettingsRepository,
    internal val collectionManager: CollectionsRepository,
    internal val recentlyPlayedManager: RecentlyPlayedRepository,
    internal val platformResolver: PlatformConfig,
    internal val installedCoreService: InstalledCoreService,
    internal val updateManager: dev.cannoli.scorza.updater.UpdateManager,
    internal val atomicRename: AtomicRename,
    internal val scanner: RomScanner,
    internal val romDirectoryWalker: RomDirectoryWalker,
    internal val settingsViewModel: SettingsViewModel,
    internal val gameListViewModel: GameListViewModel,
    internal val systemListViewModel: SystemListViewModel,
    internal val romsRepository: RomsRepository,
    internal val gameOverrideStore: dev.cannoli.scorza.db.GameOverrideStore,
    internal val appsRepository: AppsRepository,
    internal val artworkLookup: dev.cannoli.scorza.util.ArtworkLookup,
    internal val launcherActions: LauncherActions,
    internal val activityActions: ActivityActions,
    internal val controllersViewModel: dev.cannoli.scorza.ui.viewmodel.ControllersViewModel,
    internal val emulatorMappingBuilder: EmulatorMappingBuilder,
    internal val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
    internal val rommDownloader: dev.cannoli.scorza.download.Downloader,
    internal val rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel,
    internal val rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher,
    internal val raPreloadController: dev.cannoli.scorza.achievements.RaPreloadController,
    internal val deviceRegistrar: dev.cannoli.scorza.romm.sync.DeviceRegistrar,
    internal val saveSyncService: dev.cannoli.scorza.romm.sync.SaveSyncService,
    internal val slotManager: dev.cannoli.scorza.romm.sync.SlotManager,
    internal val saveSlotsHandler: dev.cannoli.scorza.input.screen.SaveSlotsInputHandler,
    internal val coreUpdateController: CoreUpdateController,
    internal val shaderUpdateController: ShaderUpdateController,
    internal val syncHistoryStore: dev.cannoli.scorza.romm.sync.SyncHistoryStore,
    internal val pendingConflictStore: dev.cannoli.scorza.romm.sync.PendingConflictStore,
    internal val saveSyncStatusHolder: dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder,
    internal val osdController: dev.cannoli.ui.components.OsdController,
    internal val rommDevicePairing: dev.cannoli.scorza.romm.RommDevicePairing,
) : DialogPrecedence {
    internal val applyingConflicts = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val quickMenuRebuild = java.util.concurrent.atomic.AtomicBoolean(false)
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

    private suspend fun quickMenuState(selected: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow? = null): DialogState.QuickMenu {
        val conflicts = saveSyncService.pendingConflictCount()
        val errors = saveSyncStatusHolder.errors.value.size
        val rows = dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.visibleRows(
            rommPaired = rommStore.isConfigured,
            kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
            saveSyncEnabled = settings.rommSaveSyncEnabled,
            pendingConflicts = conflicts,
            syncErrors = errors,
            downloadCount = rommDownloader.state.value.size,
            debugBuild = dev.cannoli.scorza.BuildConfig.DEBUG,
        )
        return DialogState.QuickMenu(
            rows = rows,
            kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
            selectedIndex = selected?.let { rows.indexOf(it).coerceAtLeast(0) } ?: 0,
            conflictCount = conflicts,
            syncErrorCount = errors,
        )
    }

    // Single-flight: held Back / Menu key-repeat re-enters these branches before the
    // coroutine below completes. Without this guard a second rebuild can win the race
    // and overwrite fresher badge counts with stale ones (or, for Kitchen, double-rescan).
    // [beforeShow] runs in the same main-thread step that shows the menu, so a caller that has to
    // leave a screen first never draws the frame in between with neither the screen nor the menu.
    fun openQuickMenu(
        selected: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow? = null,
        beforeShow: (() -> Unit)? = null,
    ) {
        if (!quickMenuRebuild.compareAndSet(false, true)) {
            // A rebuild already in flight will show the menu, but only this call knows how to leave
            // the screen behind it, so Back never strands the user on it.
            beforeShow?.invoke()
            return
        }
        ioScope.launch {
            try {
                val st = quickMenuState(selected)
                withContext(Dispatchers.Main) {
                    beforeShow?.invoke()
                    nav.dialogState.value = st
                }
            } finally {
                quickMenuRebuild.set(false)
            }
        }
    }

    override fun onMenu(): Boolean {
        val ds = nav.dialogState.value
        if (ds is KeyboardHost) {
            nav.dialogState.value = DialogState.KeyboardHelp(ds, ds.keyboard.layout)
            return true
        }
        if (ds is DialogState.KeyboardHelp) {
            nav.dialogState.value = ds.restore
            return true
        }
        if (ds != DialogState.None) return false
        if (isRommScreen()) {
            if (rommDownloader.state.value.isEmpty()) return true
            nav.dialogState.value = rommActionsPicker(hasDownloads = true)
            return true
        }
        if (isQuickMenuBlockedScreen()) return false
        openQuickMenu()
        return true
    }

    // Screens that mean something else by the menu button, so it has to reach their own handler.
    private fun isQuickMenuBlockedScreen(): Boolean = when (nav.currentScreen) {
        is LauncherScreen.Settings,
        is LauncherScreen.InputTester,
        is LauncherScreen.EditButtons,
        is LauncherScreen.ShortcutBinding,
        is LauncherScreen.Guide,
        is LauncherScreen.OnboardingScreen -> true
        else -> false
    }

    internal var pendingContextReturn: ContextReturn? = null
    var openGuides: ((dev.cannoli.scorza.model.Rom) -> Unit)? = null
    // The RetroAchievements screen owns the credential-clearing and its own pop, so the confirm
    // dialog delegates back to it rather than duplicating that navigation here.
    var onRetroAchievementsLogout: (() -> Unit)? = null

    internal val gameContextOptions = listOf(MENU_MANAGE_COLLECTIONS, MENU_EMULATOR_OVERRIDE, MENU_RA_GAME_ID, MENU_FORCE_SOFTCORE, MENU_RENAME, MENU_DELETE_GAME)

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
            is DialogState.QuickInfo, is DialogState.Kitchen -> {}
            is ListDialog -> moveSelection(ds, -1)
            else -> {}
        }
        return true
    }

    /** The one place that knows how many rows a [ListDialog] has; some counts are not its to own. */
    private fun listSize(ds: ListDialog): Int = when (ds) {
        is DialogState.Picker -> ds.items.size
        is DialogState.SyncHistory -> ds.entries.size
        is DialogState.SyncErrors -> ds.errors.size
        is DialogState.ConflictsMenu -> ds.rows.size
        is DialogState.RommDownloads -> rommDownloader.state.value.size
        is DialogState.QuickMenu -> ds.rows.size
        is DialogState.QuickInfo -> ds.endpoints.size
        is DialogState.Kitchen -> ds.urls.size
        is DialogState.RommArtResults -> artResultRowCount(ds)
    }

    private fun moveSelection(ds: ListDialog, delta: Int) {
        val size = listSize(ds)
        if (size <= 0) return
        nav.dialogState.value = ds.withSelectedIndex((ds.selectedIndex + delta).mod(size))
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
            is DialogState.QuickInfo, is DialogState.Kitchen -> {}
            is ListDialog -> moveSelection(ds, 1)
            else -> {}
        }
        return true
    }

    override fun onLeft(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.Picker -> ds.onCycle?.invoke(ds.selectedIndex, -1)
            is DialogState.ConflictsMenu -> cycleConflictChoice(ds, -1)
            is DialogState.QuickInfo, is DialogState.Kitchen -> moveSelection(ds, -1)
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
            is DialogState.Picker -> ds.onCycle?.invoke(ds.selectedIndex, 1)
            is DialogState.ConflictsMenu -> cycleConflictChoice(ds, 1)
            is DialogState.QuickInfo, is DialogState.Kitchen -> moveSelection(ds, 1)
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

    override fun onConfirm(): Boolean = confirmDialog()

    override fun onBack(): Boolean = backDialog()

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
            is DialogState.ConflictsMenu -> applyAllConflicts(ds)
            else -> {}
        }
        return true
    }

    override fun onNorth(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) {
            if (nav.currentScreen is LauncherScreen.RommPlatformList) {
                nav.dialogState.value = rommSettingsPicker()
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
                nav.screenStack.add(LauncherScreen.Credits(fromQuickMenu = ds.fromQuickMenu))
            }
            is DialogState.Kitchen -> {
                dev.cannoli.scorza.server.KitchenManager.stop(context)
                if (ds.fromQuickMenu) openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN)
                else nav.dialogState.value = DialogState.None
                launcherActions.rescanSystemList()
            }
            is DialogState.ColorPicker -> {
                val currentHex = settingsViewModel.getColorHex(ds.settingKey).removePrefix("#")
                nav.dialogState.value = DialogState.HexColorInput(
                    settingKey = ds.settingKey,
                    title = ds.title,
                    currentHex = currentHex
                )
            }
            is DialogState.RommDownloads -> if (rommDownloader.activeCount() >= 2) {
                nav.dialogState.value = DialogState.RommConfirm(dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL, fromQuickMenu = ds.fromQuickMenu)
            }
            else -> {}
        }
        return true
    }

    override fun onWest(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RommDownloads -> {
                rommDownloader.clearFinished()
                // The selection can be past the end once the finished rows go, and an empty queue
                // has nothing left to show.
                val left = rommDownloader.state.value
                if (left.isEmpty()) {
                    if (ds.fromQuickMenu) openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DOWNLOADS)
                    else nav.dialogState.value = DialogState.None
                } else {
                    nav.dialogState.value =
                        ds.copy(selectedIndex = ds.selectedIndex.coerceAtMost(left.size - 1))
                }
            }
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
                    nav.dialogState.value = DialogState.UpdateDownload(info.versionName, info.changelog, ds.fromQuickMenu)
                    ioScope.launch { updateManager.downloadAndInstall(info) }
                }
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

    internal fun updateColorListOnStack(settingKey: String, entries: List<ColorEntry>) {
        val cl = nav.currentScreen
        if (cl is LauncherScreen.ColorList) {
            nav.screenStack[nav.screenStack.lastIndex] = cl.copy(
                colors = entries,
                selectedIndex = entries.indexOfFirst { it.key == settingKey }.coerceAtLeast(0)
            )
        }
    }

    internal fun romDir(): File =
        settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(File(settings.sdCardRoot), "Roms")

    internal fun relativeRomPath(file: File): String? {
        val romDir = romDir()
        return try {
            val relative = file.relativeTo(romDir).path
            if (relative.startsWith("..")) null else relative
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal sealed interface ContextReturn {
    data class Single(val gameName: String, val options: List<String>, val selectedOption: Int = 0) : ContextReturn
    data class Bulk(val gamePaths: List<String>, val options: List<String>) : ContextReturn
}
