package dev.cannoli.scorza.input

import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.recentKey
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.components.HEX_KEYS
import dev.cannoli.ui.components.HEX_ROW_SIZE
import dev.cannoli.ui.components.getKeyboardRows
import dev.cannoli.ui.components.handleKeyboardConfirm
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.ui.screens.asKeyboardState
import dev.cannoli.scorza.ui.screens.withBackspace
import dev.cannoli.scorza.ui.screens.withCaps
import dev.cannoli.scorza.ui.screens.withCursor
import dev.cannoli.scorza.ui.screens.withInsertedChar
import dev.cannoli.scorza.ui.screens.withKeyboard
import dev.cannoli.scorza.ui.screens.withMenuDelta
import dev.cannoli.scorza.ui.screens.withSymbols
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.theme.COLOR_PRESETS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DialogInputHandler(
    private val nav: NavigationController,
    private val ioScope: CoroutineScope,
    private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val scanner: RomScanner,
    private val collectionManager: CollectionsRepository,
    private val recentlyPlayedManager: RecentlyPlayedRepository,
    private val platformResolver: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val launchManager: LaunchManager,
    private val updateManager: dev.cannoli.scorza.updater.UpdateManager,
    private val profileManager: ProfileManager,
    private val atomicRename: AtomicRename,
    private val settingsViewModel: SettingsViewModel,
    private val gameListViewModel: GameListViewModel,
    private val systemListViewModel: SystemListViewModel,
    private val controllerManager: ControllerManager,
    private val autoconfigMatcher: AutoconfigMatcher,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val onRescanSystemList: () -> Unit,
    private val onFinishAffinity: () -> Unit,
    private val onRestartApp: () -> Unit,
    private val openColorPickerFn: (settingKey: String) -> Unit,
    private val onSystemListRename: (DialogState.RenameInput) -> Unit,
    private val onStartRaLogin: (username: String, password: String) -> Unit,
) {
    var selectDown = false
    var selectHeld = false
    var capsBeforeSymbols = false
    private val selectHoldHandler = Handler(Looper.getMainLooper())
    private val selectHoldRunnable = Runnable {
        selectHeld = true
        val ds = nav.dialogState.value
        if (ds is KeyboardInputState) {
            val ks = ds.asKeyboardState()!!
            if (!ks.symbols) capsBeforeSymbols = ks.caps
            nav.dialogState.value = ds.withCaps(false).withSymbols(!ks.symbols)
        }
    }

    fun cancelSelectHold() {
        selectHoldHandler.removeCallbacks(selectHoldRunnable)
    }

    private sealed interface ContextReturn {
        data class Single(val gameName: String, val options: List<String>, val selectedOption: Int = 0) : ContextReturn
        data class Bulk(val gamePaths: List<String>, val options: List<String>) : ContextReturn
    }
    private var pendingContextReturn: ContextReturn? = null
    var pendingFghItem: ListItem? = null

    companion object {
        const val MENU_RENAME = "Rename"
        const val MENU_DELETE = "Delete"
        const val MENU_DELETE_GAME = "Delete Game"
        const val MENU_DELETE_ART = "Delete Art"
        const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
        const val MENU_EMULATOR_OVERRIDE = "Emulator Override"
        const val MENU_REMOVE_FROM_COLLECTION = "Remove From Collection"
        const val MENU_CHILD_COLLECTIONS = "Child Collections"
        const val MENU_RA_GAME_ID = "RA Game ID"
        const val MENU_ADD_FAVORITE = "Add To Favorites"
        const val MENU_REMOVE_FAVORITE = "Remove From Favorites"
        const val MENU_REMOVE = "Remove Shortcut"
        const val MENU_REMOVE_FROM_RECENTS = "Remove From Recently Played"
    }

    private val gameContextOptions = listOf(MENU_MANAGE_COLLECTIONS, MENU_EMULATOR_OVERRIDE, MENU_RA_GAME_ID, MENU_RENAME, MENU_DELETE_GAME)

    fun onUp(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu,
            is DialogState.BulkContextMenu -> {
                ds.withMenuDelta(-1)?.let { nav.dialogState.value = it }
            }
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                val rows = getKeyboardRows(ks.caps, ks.symbols)
                val newRow = if (ks.keyRow <= 0) rows.lastIndex else ks.keyRow - 1
                val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                nav.dialogState.value = ds.withKeyboard(newRow, newCol)
            }
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
            else -> {}
        }
        return true
    }

    fun onDown(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu,
            is DialogState.BulkContextMenu -> {
                ds.withMenuDelta(1)?.let { nav.dialogState.value = it }
            }
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                val rows = getKeyboardRows(ks.caps, ks.symbols)
                val newRow = if (ks.keyRow >= rows.lastIndex) 0 else ks.keyRow + 1
                val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                nav.dialogState.value = ds.withKeyboard(newRow, newCol)
            }
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
            else -> {}
        }
        return true
    }

    fun onLeft(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.Kitchen -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex - 1 + ds.urls.size) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                val rows = getKeyboardRows(ks.caps, ks.symbols)
                val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                val newCol = if (ks.keyCol <= 0) rowSize - 1 else ks.keyCol - 1
                nav.dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
            }
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

    fun onRight(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.Kitchen -> {
                if (ds.urls.size > 1) {
                    val newIdx = (ds.selectedIndex + 1) % ds.urls.size
                    nav.dialogState.value = ds.copy(selectedIndex = newIdx)
                }
            }
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                val rows = getKeyboardRows(ks.caps, ks.symbols)
                val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                val newCol = if (ks.keyCol >= rowSize - 1) 0 else ks.keyCol + 1
                nav.dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
            }
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

    fun onConfirm(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.ContextMenu -> onContextMenuConfirm(ds)
            is DialogState.BulkContextMenu -> onBulkContextMenuConfirm(ds)
            is DialogState.DeleteConfirm -> onDeleteConfirm(ds)
            is DialogState.RenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                onChar = { name, pos -> nav.dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                onShift = { nav.dialogState.value = ds.copy(caps = !ds.caps) },
                onSymbols = { nav.dialogState.value = ds.copy(symbols = !ds.symbols) },
                onEnter = { onRenameConfirm(ds) }
            )
            is DialogState.NewCollectionInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                onChar = { name, pos -> nav.dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                onShift = { nav.dialogState.value = ds.copy(caps = !ds.caps) },
                onSymbols = { nav.dialogState.value = ds.copy(symbols = !ds.symbols) },
                onEnter = { onNewCollectionConfirm(ds) }
            )
            is DialogState.CollectionRenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                onChar = { name, pos -> nav.dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                onShift = { nav.dialogState.value = ds.copy(caps = !ds.caps) },
                onSymbols = { nav.dialogState.value = ds.copy(symbols = !ds.symbols) },
                onEnter = { onCollectionRenameConfirm(ds) }
            )
            is DialogState.ProfileNameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                onChar = { name, pos -> nav.dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                onShift = { nav.dialogState.value = ds.copy(caps = !ds.caps) },
                onSymbols = { nav.dialogState.value = ds.copy(symbols = !ds.symbols) },
                onEnter = { onProfileNameConfirm(ds) }
            )
            is DialogState.NewFolderInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                onChar = { name, pos -> nav.dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                onShift = { nav.dialogState.value = ds.copy(caps = !ds.caps) },
                onSymbols = { nav.dialogState.value = ds.copy(symbols = !ds.symbols) },
                onEnter = { onNewFolderConfirm(ds) }
            )
            is DialogState.QuitConfirm -> {
                onFinishAffinity()
            }
            is DialogState.DeleteProfileConfirm -> {
                profileManager.deleteProfile(ds.profileName)
                val updated = profileManager.listProfiles()
                val screen = nav.currentScreen as? LauncherScreen.ProfileList
                if (screen != null) {
                    nav.screenStack[nav.screenStack.lastIndex] = screen.copy(
                        profiles = updated,
                        selectedIndex = screen.selectedIndex.coerceAtMost(updated.lastIndex)
                    )
                }
                nav.dialogState.value = DialogState.None
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
                            onRescanSystemList()
                        }
                    }
                }
            }
            is DialogState.DeleteCollectionConfirm -> {
                val stem = ds.collectionStem
                val glState = gameListViewModel.state.value
                val deletingFromParent = glState.isCollection && !glState.isCollectionsList
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                if (!deletingFromParent) gameListViewModel.saveCollectionsPosition()
                ioScope.launch {
                    val id = collectionManager.all().firstOrNull { it.displayName == stem }?.id
                    if (id != null) collectionManager.delete(id)
                    if (deletingFromParent) {
                        gameListViewModel.reload()
                        onRescanSystemList()
                    } else {
                        if (settings.contentMode == dev.cannoli.scorza.settings.ContentMode.COLLECTIONS) {
                            withContext(Dispatchers.Main) {
                                nav.screenStack.removeAt(nav.screenStack.lastIndex)
                                onRescanSystemList()
                            }
                        } else {
                            val remaining = collectionManager.topLevel()
                            if (remaining.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    nav.screenStack.removeAt(nav.screenStack.lastIndex)
                                    onRescanSystemList()
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
                onRestartApp()
            }
            else -> {}
        }
        return true
    }

    fun onBack(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                ds.withBackspace()?.let { nav.dialogState.value = it }
            }
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
            is DialogState.DeleteConfirm,
            is DialogState.DeleteCollectionConfirm -> {
                restoreContextMenu()
            }
            is DialogState.DeleteProfileConfirm,
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
            is DialogState.About,
            is DialogState.Kitchen -> {
                nav.dialogState.value = DialogState.None
                onRescanSystemList()
            }
            is DialogState.RAAccount -> {
                nav.dialogState.value = DialogState.None
                if (settingsViewModel.state.value.inSubList) settingsViewModel.exitSubList()
            }
            is DialogState.RALoggingIn -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.RestartRequired -> {}
            else -> {}
        }
        return true
    }

    fun onStart(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput -> onRenameConfirm(ds)
            is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
            is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
            is DialogState.ProfileNameInput -> onProfileNameConfirm(ds)
            is DialogState.NewFolderInput -> onNewFolderConfirm(ds)
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

    fun onNorth(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                ds.withInsertedChar(" ")?.let { nav.dialogState.value = it }
            }
            is DialogState.About -> {
                nav.dialogState.value = DialogState.None
                nav.screenStack.add(LauncherScreen.Credits())
            }
            is DialogState.Kitchen -> {
                dev.cannoli.scorza.server.KitchenManager.stop()
                nav.dialogState.value = DialogState.None
                onRescanSystemList()
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
            else -> {}
        }
        return true
    }

    fun onWest(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.CollectionRenameInput -> {
                restoreContextMenu()
            }
            is DialogState.NewCollectionInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                nav.dialogState.value = DialogState.None
            }
            is DialogState.HexColorInput -> {
                openColorPickerFn(ds.settingKey)
            }
            is DialogState.About -> {
                val info = updateManager.updateAvailable.value
                if (info != null) {
                    nav.dialogState.value = DialogState.UpdateDownload(info.versionName, info.changelog)
                    ioScope.launch { updateManager.downloadAndInstall(info) }
                }
            }
            else -> {}
        }
        return true
    }

    fun onSelect(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                if (!selectDown) {
                    selectDown = true
                    selectHeld = false
                    selectHoldHandler.postDelayed(selectHoldRunnable, 400)
                }
            }
            else -> {}
        }
        return true
    }

    fun onSelectUp(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        if (ds is KeyboardInputState) {
            cancelSelectHold()
            if (!selectHeld) {
                val ks = ds.asKeyboardState()!!
                if (ks.symbols) {
                    nav.dialogState.value = ds.withCaps(capsBeforeSymbols).withSymbols(false)
                } else {
                    nav.dialogState.value = ds.withCaps(!ks.caps)
                }
            }
            selectDown = false
            selectHeld = false
            return true
        }
        return false
    }

    fun onL1(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                if (ks.cursorPos > 0) nav.dialogState.value = ds.withCursor(ks.cursorPos - 1)
            }
            else -> {}
        }
        return true
    }

    fun onR1(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                if (ks.cursorPos < ks.currentName.length) nav.dialogState.value = ds.withCursor(ks.cursorPos + 1)
            }
            else -> {}
        }
        return true
    }

    fun onL2(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput -> {
                nav.dialogState.value = ds.withCursor(0)
            }
            else -> {}
        }
        return true
    }

    fun onR2(): Boolean {
        val ds = nav.dialogState.value
        if (ds == DialogState.None) return false
        when (ds) {
            is DialogState.RenameInput,
            is DialogState.NewCollectionInput,
            is DialogState.CollectionRenameInput,
            is DialogState.ProfileNameInput,
            is DialogState.NewFolderInput -> {
                val ks = ds.asKeyboardState()!!
                nav.dialogState.value = ds.withCursor(ks.currentName.length)
            }
            else -> {}
        }
        return true
    }

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (nav.currentScreen is LauncherScreen.ProfileList) {
            when (state.options[state.selectedOption]) {
                "Rename" -> {
                    nav.dialogState.value = DialogState.ProfileNameInput(
                        isNew = false,
                        originalName = state.gameName,
                        currentName = state.gameName,
                        cursorPos = state.gameName.length
                    )
                }
                "Delete" -> {
                    nav.dialogState.value = DialogState.DeleteProfileConfirm(state.gameName)
                }
            }
            return
        }
        if (nav.currentScreen == LauncherScreen.SystemList) {
            val fghItem = pendingFghItem
            if (fghItem != null) {
                pendingFghItem = null
                onFghContextMenuConfirm(fghItem, state)
            } else {
                when (state.options[state.selectedOption]) {
                    MENU_RENAME -> {
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = state.gameName,
                            currentName = state.gameName,
                            cursorPos = state.gameName.length
                        )
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
                item.recentKey()?.let { clearRecentlyPlayedByPath(it) }
                ioScope.launch {
                    gameListViewModel.loadRecentlyPlayed()
                    onRescanSystemList()
                }
                return
            }
            selected == MENU_RENAME -> {
                if (collection != null) {
                    nav.dialogState.value = DialogState.CollectionRenameInput(
                        oldStem = collection.stem,
                        currentName = displayName,
                        cursorPos = displayName.length
                    )
                } else {
                    nav.dialogState.value = DialogState.RenameInput(
                        gameName = displayName,
                        currentName = displayName,
                        cursorPos = displayName.length
                    )
                }
            }
            selected == MENU_DELETE || selected == MENU_DELETE_GAME -> {
                if (collection != null) {
                    nav.dialogState.value = DialogState.DeleteCollectionConfirm(collectionStem = collection.stem)
                } else {
                    nav.dialogState.value = DialogState.DeleteConfirm(gameName = displayName)
                }
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                val path = item.recentKey() ?: return
                openCollectionManager(listOf(path), displayName)
            }
            selected == MENU_CHILD_COLLECTIONS -> {
                if (collection != null) openChildPicker(collection.stem)
            }
            selected == MENU_DELETE_ART -> {
                if (rom != null) {
                    pendingContextReturn = null
                    rom.artFile?.delete()
                    scanner.invalidatePlatform(rom.platformTag)
                    gameListViewModel.reload()
                    nav.dialogState.value = DialogState.None
                }
            }
            selected == MENU_RA_GAME_ID -> {
                if (rom != null) {
                    val current = rom.raGameId?.toString() ?: ""
                    nav.dialogState.value = DialogState.RenameInput(
                        gameName = "ra_game_id:${rom.path.absolutePath}",
                        currentName = current,
                        cursorPos = current.length
                    )
                }
            }
            selected == MENU_REMOVE -> {
                if (app != null) {
                    pendingContextReturn = null
                    ioScope.launch {
                        appsRepository.delete(app.id)
                        gameListViewModel.reload()
                        onRescanSystemList()
                    }
                    nav.dialogState.value = DialogState.None
                }
            }
            selected == MENU_ADD_FAVORITE || selected == MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                gameListViewModel.toggleFavorite { onRescanSystemList() }
                nav.dialogState.value = DialogState.None
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                if (rom == null) return
                val tag = rom.platformTag
                val bundledCoresDir2 = LaunchManager.extractBundledCores(context)
                val options = platformResolver.getCorePickerOptions(tag, context.packageManager,
                    installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir2,
                    unresponsivePackages = installedCoreService.unresponsivePackages)
                val platformCoreId = platformResolver.getCoreMapping(tag)
                val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
                val defaultLabel = if (platformCoreName.isNotEmpty()) "Platform Setting ($platformCoreName)" else "Platform Setting"
                val defaultOption = CorePickerOption("", defaultLabel, "")
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
                nav.screenStack.add(LauncherScreen.CorePicker(
                    tag = tag,
                    platformName = rom.displayName,
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = rom.path.absolutePath,
                    activeIndex = selectedIdx
                ))
            }
        }
    }

    private fun onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
        pendingContextReturn = ContextReturn.Bulk(state.gamePaths, state.options)
        when (state.options[state.selectedOption]) {
            MENU_REMOVE_FROM_RECENTS -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                state.gamePaths.forEach { path -> clearRecentlyPlayedByPath(path) }
                ioScope.launch {
                    gameListViewModel.loadRecentlyPlayed()
                    onRescanSystemList()
                }
                return
            }
            MENU_ADD_FAVORITE -> {
                pendingContextReturn = null
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        addPathToCollectionByName("Favorites", path)
                    }
                    gameListViewModel.reload()
                    onRescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        removePathFromCollectionByName("Favorites", path)
                    }
                    gameListViewModel.reload()
                    onRescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(state.gamePaths, "${state.gamePaths.size} Selected")
            }
            MENU_DELETE_GAME -> {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.DeleteConfirm(
                    gameName = "${state.gamePaths.size} items",
                    bulkPaths = state.gamePaths
                )
            }
            MENU_DELETE_ART -> {
                pendingContextReturn = null
                val pathSet = state.gamePaths.toSet()
                val tagsToInvalidate = mutableSetOf<String>()
                gameListViewModel.state.value.items
                    .filterIsInstance<ListItem.RomItem>()
                    .filter { it.rom.path.absolutePath in pathSet }
                    .forEach { romItem ->
                        romItem.rom.artFile?.delete()
                        tagsToInvalidate.add(romItem.rom.platformTag)
                    }
                tagsToInvalidate.forEach { scanner.invalidatePlatform(it) }
                gameListViewModel.reload()
                nav.dialogState.value = DialogState.None
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
                    onRescanSystemList()
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
                    onRescanSystemList()
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
                onRescanSystemList()
                withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
            }
        } else {
            val item = gameListViewModel.getSelectedItem()
                ?: (systemListViewModel.getSelectedItem() as? SystemListViewModel.ListItem.GameItem)?.item
            val rom = (item as? ListItem.RomItem)?.rom ?: return
            ioScope.launch {
                deleteRom(rom)
                gameListViewModel.reload()
                onRescanSystemList()
                withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
            }
        }
    }

    fun onCollectionPickerConfirm(state: LauncherScreen.CollectionPicker) {
        val added = state.checkedIndices - state.initialChecked
        val removed = state.initialChecked - state.checkedIndices
        val toAdd = added.mapNotNull { state.collections.getOrNull(it) }
        val toRemove = removed.mapNotNull { state.collections.getOrNull(it) }
        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            ioScope.launch {
                for (path in state.gamePaths) {
                    toAdd.forEach { collName -> addPathToCollectionByName(collName, path) }
                    toRemove.forEach { collName -> removePathFromCollectionByName(collName, path) }
                }
                gameListViewModel.reload()
                onRescanSystemList()
            }
        }
        nav.screenStack.removeAt(nav.screenStack.lastIndex)
        restoreContextMenu()
    }

    fun onChildPickerConfirm(screen: LauncherScreen.ChildPicker) {
        val parent = collectionManager.all().firstOrNull { it.displayName == screen.collectionName }
        if (parent == null) {
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            restoreContextMenu()
            return
        }
        val selectedNames = screen.checkedIndices.mapNotNull { screen.collections.getOrNull(it) }.toSet()
        val all = collectionManager.all()
        val targetChildIds = all.filter { it.displayName in selectedNames }.map { it.id }.toSet()
        val currentChildIds = collectionManager.children(parent.id).map { it.id }.toSet()
        ioScope.launch {
            (targetChildIds - currentChildIds).forEach { collectionManager.setParent(it, parent.id) }
            (currentChildIds - targetChildIds).forEach { collectionManager.setParent(it, null) }
            gameListViewModel.reload()
            onRescanSystemList()
        }
        nav.screenStack.removeAt(nav.screenStack.lastIndex)
        restoreContextMenu()
    }

    fun onCorePickerConfirm(screen: LauncherScreen.CorePicker) {
        val chosen = screen.cores.getOrNull(screen.selectedIndex) ?: return
        if (screen.gamePath != null) {
            if (chosen.coreId.isEmpty() && chosen.appPackage == null) {
                platformResolver.setGameOverride(screen.gamePath, null, null)
                platformResolver.setGameAppOverride(screen.gamePath, null)
            } else if (chosen.appPackage != null) {
                platformResolver.setGameAppOverride(screen.gamePath, chosen.appPackage)
            } else {
                platformResolver.setGameOverride(screen.gamePath, chosen.coreId, chosen.runnerLabel, chosen.raPackage)
            }
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            restoreContextMenu()
        } else {
            if (chosen.appPackage != null) {
                platformResolver.setAppMapping(screen.tag, chosen.appPackage)
            } else {
                platformResolver.setCoreMapping(screen.tag, chosen.coreId, chosen.runnerLabel, chosen.raPackage)
            }
            platformResolver.saveCoreMappings()
            nav.screenStack.removeAt(nav.screenStack.lastIndex)
            val cm = nav.screenStack.lastOrNull()
            if (cm is LauncherScreen.CoreMapping) {
                val all = platformResolver.getDetailedMappings(context.packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(context), installedCoreService.unresponsivePackages)
                val filtered = filterCoreMappings(all, cm.filter)
                val idx = filtered.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
            }
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
        } || (glState.isCollection && glState.collectionName == "Favorites")
        return buildList {
            if (glState.platformTag == "recently_played") add(MENU_REMOVE_FROM_RECENTS)
            add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
            if (isApk) {
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_REMOVE)
            } else {
                addAll(gameContextOptions.map { menuItem ->
                    if (menuItem == MENU_EMULATOR_OVERRIDE && romPath != null) {
                        val bundledCoresDir = LaunchManager.extractBundledCores(context)
                        val options = platformResolver.getCorePickerOptions(platformTag, context.packageManager,
                            installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir,
                            unresponsivePackages = installedCoreService.unresponsivePackages)
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
                    } else menuItem
                })
                if (rom?.artFile != null) {
                    val idx = indexOf(MENU_DELETE_GAME)
                    if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
                }
            }
        }
    }

    fun openCollectionManager(gamePaths: List<String>, title: String) {
        val all = collectionManager.all().filter { it.type == CollectionType.STANDARD }
        val stems = all.map { it.displayName }
        val displayNames = all.map { it.displayName }
        val alreadyIn = collectionsContainingPaths(gamePaths, all)
        val initialChecked = stems.indices
            .filter { stems[it] in alreadyIn }
            .toSet()
        nav.dialogState.value = DialogState.None
        nav.screenStack.add(LauncherScreen.CollectionPicker(
            gamePaths = gamePaths,
            title = title,
            collections = stems,
            displayNames = displayNames,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    fun openChildPicker(collectionStem: String) {
        val parent = collectionManager.all().firstOrNull { it.displayName == collectionStem } ?: return
        val all = collectionManager.all().filter { it.type == CollectionType.STANDARD }
        val ancestorIds = collectionManager.ancestors(parent.id).map { it.id }.toSet() + parent.id
        val available = all.filter { it.id !in ancestorIds }
        val availableNames = available.map { it.displayName }
        val displayNames = available.map { it.displayName }
        val currentChildIds = collectionManager.children(parent.id).map { it.id }.toSet()
        val initialChecked = available.indices
            .filter { available[it].id in currentChildIds }
            .toSet()
        nav.dialogState.value = DialogState.None
        nav.screenStack.add(LauncherScreen.ChildPicker(
            collectionName = collectionStem,
            collections = availableNames,
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

    private fun onNewCollectionConfirm(state: DialogState.NewCollectionInput) {
        val name = state.currentName.trim()
        if (name.isEmpty()) {
            nav.dialogState.value = DialogState.None
            return
        }
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            val newId = collectionManager.create(name)
            if (state.parentStem != null) {
                val parentId = collectionManager.all().firstOrNull { it.displayName == state.parentStem }?.id
                if (parentId != null) collectionManager.setParent(newId, parentId)
            }
            state.gamePaths.forEach { path ->
                resolvePathToRef(path)?.let { collectionManager.addMember(newId, it) }
            }
            gameListViewModel.reload()
            onRescanSystemList()
            withContext(Dispatchers.Main) { refreshCollectionPickerOnStack() }
        }
    }

    private fun onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == dev.cannoli.scorza.model.Collection.stemToDisplayName(state.oldStem)) {
            restoreContextMenu()
            return
        }
        val glState = gameListViewModel.state.value
        val renamingFromParent = glState.isCollection && !glState.isCollectionsList
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            val id = collectionManager.all().firstOrNull { it.displayName == state.oldStem }?.id
            if (id != null) collectionManager.rename(id, newName)
            if (renamingFromParent) {
                gameListViewModel.reload()
            } else {
                gameListViewModel.loadCollectionsList(restoreIndex = true)
            }
        }
    }

    private fun onProfileNameConfirm(state: DialogState.ProfileNameInput) {
        val name = state.currentName.trim()
        if (name.isBlank() || ProfileManager.isProtected(name)) {
            nav.dialogState.value = DialogState.None
            return
        }
        if (state.isNew) {
            val identity = controllerManager.slots[0]
            val device = controllerManager.getDeviceIdForPort(0)?.let { android.view.InputDevice.getDevice(it) }
            val verifier: (IntArray) -> BooleanArray = if (device != null) {
                { codes -> device.hasKeys(*codes) }
            } else {
                { codes -> BooleanArray(codes.size) { true } }
            }
            val match = identity?.let { profileManager.autoProfileControlsFor(it, autoconfigMatcher, verifier) }
            val controls = match?.controls ?: emptyMap()
            if (!profileManager.createProfile(name, controls)) {
                nav.dialogState.value = DialogState.None
                return
            }
            if (match != null) {
                nav.osdMessage = "Prefilled with ${match.deviceName}"
            }
        } else {
            val file = File(settings.sdCardRoot, "Config/Profiles/${state.originalName}.ini")
            val dest = File(settings.sdCardRoot, "Config/Profiles/$name.ini")
            if (dest.exists() && name != state.originalName) {
                nav.dialogState.value = DialogState.None
                return
            }
            file.renameTo(dest)
        }
        val updated = profileManager.listProfiles()
        val screen = nav.currentScreen as? LauncherScreen.ProfileList
        if (screen != null) {
            nav.screenStack[nav.screenStack.lastIndex] = screen.copy(
                profiles = updated,
                selectedIndex = updated.indexOf(name).coerceAtLeast(0)
            )
        }
        nav.dialogState.value = DialogState.None
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
            onStartRaLogin(settings.raUsername, settingsViewModel.raPassword)
            nav.dialogState.value = DialogState.None
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
            }
            restoreContextMenu()
            return
        }
        if (nav.currentScreen == LauncherScreen.SystemList) {
            onSystemListRename(state)
            return
        }
        val item = gameListViewModel.getSelectedItem() ?: return
        val newName = state.currentName.trim()
        val currentName = when (item) {
            is ListItem.RomItem -> item.rom.displayName
            is ListItem.SubfolderItem -> item.name
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
            if (item is ListItem.SubfolderItem) {
                val tag = gameListViewModel.state.value.platformTag
                val romDir = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(File(settings.sdCardRoot), "Roms")
                val oldDir = File(romDir, "$tag${File.separator}${item.path}")
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
                        nav.dialogState.value = DialogState.RenameResult(false, "Failed to rename directory")
                    }
                }
                scanner.invalidatePlatform(tag)
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
                        romsRepository.updateRomPath(rom.id, newRelative)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.RenameResult(false, result.error ?: "Rename failed")
                    }
                }
            }
            scanner.invalidatePlatform(rom.platformTag)
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
                    onRescanSystemList()
                }
                nav.dialogState.value = DialogState.None
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(listOf(path), displayName)
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                if (rom == null) return
                val tag = rom.platformTag
                val bundledCoresDir2 = LaunchManager.extractBundledCores(context)
                val options = platformResolver.getCorePickerOptions(tag, context.packageManager,
                    installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir2,
                    unresponsivePackages = installedCoreService.unresponsivePackages)
                val platformCoreId = platformResolver.getCoreMapping(tag)
                val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
                val defaultLabel = if (platformCoreName.isNotEmpty()) "Platform Setting ($platformCoreName)" else "Platform Setting"
                val defaultOption = CorePickerOption("", defaultLabel, "")
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
                nav.screenStack.add(LauncherScreen.CorePicker(
                    tag = tag,
                    platformName = rom.displayName,
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = rom.path.absolutePath,
                    activeIndex = selectedIdx
                ))
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
            val stems = all.map { it.displayName }
            val displayNames = all.map { it.displayName }
            val alreadyIn = collectionsContainingPaths(cp.gamePaths, all)
            val newInitialChecked = stems.indices
                .filter { stems[it] in alreadyIn }
                .toSet()
            val oldCheckedStems = cp.checkedIndices.mapNotNull { cp.collections.getOrNull(it) }.toSet()
            val newCheckedIndices = stems.indices
                .filter { stems[it] in oldCheckedStems || stems[it] in alreadyIn }
                .toSet()
            nav.screenStack[nav.screenStack.lastIndex] = cp.copy(
                collections = stems,
                displayNames = displayNames,
                checkedIndices = newCheckedIndices,
                initialChecked = newInitialChecked
            )
        }
    }

    private fun collectionsContainingPaths(gamePaths: List<String>, candidates: List<CollectionsRepository.CollectionRow>): Set<String> {
        if (gamePaths.isEmpty()) return emptySet()
        val sets = gamePaths.map { path ->
            val ref = resolvePathToRef(path) ?: return@map emptySet<String>()
            val ids = collectionManager.collectionsContaining(ref)
            candidates.asSequence().filter { it.id in ids }.map { it.displayName }.toSet()
        }
        return if (gamePaths.size == 1) sets.first()
        else sets.reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
    }

    private fun addPathToCollectionByName(collectionName: String, path: String) {
        val id = if (collectionName.equals("Favorites", ignoreCase = true)) collectionManager.favoritesId()
        else collectionManager.all().firstOrNull { it.displayName == collectionName }?.id
        val ref = resolvePathToRef(path)
        if (id != null && ref != null) collectionManager.addMember(id, ref)
    }

    private fun removePathFromCollectionByName(collectionName: String, path: String) {
        val id = if (collectionName.equals("Favorites", ignoreCase = true)) collectionManager.favoritesId()
        else collectionManager.all().firstOrNull { it.displayName == collectionName }?.id
        val ref = resolvePathToRef(path)
        if (id != null && ref != null) collectionManager.removeMember(id, ref)
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

    private fun clearRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedManager.clear(it) }
        }
    }

    private fun deleteRom(rom: dev.cannoli.scorza.model.Rom) {
        rom.path.delete()
        romsRepository.deleteRom(rom.id)
        scanner.invalidatePlatform(rom.platformTag)
    }

    private fun relativeRomPath(file: File): String? {
        val romDir = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(File(settings.sdCardRoot), "Roms")
        return try {
            val relative = file.relativeTo(romDir).path
            if (relative.startsWith("..")) null else relative
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun filterCoreMappings(all: List<dev.cannoli.scorza.ui.screens.CoreMappingEntry>, filter: Int): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> dev.cannoli.scorza.R.string.setting_color_accent
            "color_highlight" -> dev.cannoli.scorza.R.string.setting_color_highlight
            "color_highlight_text" -> dev.cannoli.scorza.R.string.setting_color_highlight_text
            "color_text" -> dev.cannoli.scorza.R.string.setting_color_text
            "color_title" -> dev.cannoli.scorza.R.string.setting_color_title
            else -> return ""
        }
        return context.getString(labelRes)
    }
}
