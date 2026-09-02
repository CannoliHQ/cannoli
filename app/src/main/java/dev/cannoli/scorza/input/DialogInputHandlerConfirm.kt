package dev.cannoli.scorza.input

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.VirtualPlatformTags
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.download.DownloadStatus
import dev.cannoli.scorza.download.inDisplayOrder
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import dev.cannoli.ui.KEY_BACKSPACE
import dev.cannoli.ui.KEY_ENTER
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.components.HEX_KEYS
import dev.cannoli.ui.components.KeyboardController
import dev.cannoli.ui.components.KeyboardPress
import dev.cannoli.ui.theme.COLOR_PRESETS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun DialogInputHandler.confirmDialog(): Boolean {
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
            if (VirtualPlatformTags.isAppList(glState.platformTag)) {
                val item = gameListViewModel.getSelectedItem()
                if (item is ListItem.AppItem) {
                    nav.dialogState.value = DialogState.None
                    ioScope.launch {
                        appsRepository.delete(item.app.id)
                        gameListViewModel.reload()
                        launcherActions.rescanSystemList()
                    }
                }
            } else {
                openEmulatorRecovery(ds.platformTag, ds.romId)
            }
        }
        is DialogState.UninstallCoreConfirm -> {
            installedCoreService.uninstall(ds.coreId)
            nav.dialogState.value = DialogState.None
            refreshInstalledCores()
        }
        is DialogState.RemoveUnusedCoresConfirm -> {
            val screen = nav.currentScreen as? LauncherScreen.InstalledCores
            screen?.rows?.filterNot { it.inUse }?.forEach { installedCoreService.uninstall(it.coreId) }
            nav.dialogState.value = DialogState.None
            osdController.show(
                context.getString(
                    dev.cannoli.scorza.R.string.osd_cores_removed,
                    android.text.format.Formatter.formatShortFileSize(context, ds.bytes),
                )
            )
            refreshInstalledCores()
        }
        is DialogState.UpdateCoresConfirm -> coreUpdateController.start()
        is DialogState.UpdateShadersConfirm -> shaderUpdateController.start()
        is DialogState.MissingCore -> openEmulatorRecovery(ds.platformTag, ds.romId)
        is DialogState.UnsupportedContent -> openEmulatorRecovery(ds.platformTag, ds.romId)
        is DialogState.NoEmulatorSet -> openEmulatorRecovery(ds.platformTag, ds.romId)
        // The one launch issue whose remedy is not the emulator picker: the emulator is right,
        // the BIOS is not, so confirm goes to the screen that lists which files are absent.
        is DialogState.MissingBios -> {
            val tag = ds.platformTag
            if (tag != null) {
                pendingContextReturn = null
                nav.dialogState.value = DialogState.None
                nav.screenStack.add(emulatorMappingBuilder.buildBiosStatus(tag, ds.platformName))
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
        // Hands straight over to the scan screen, so it must not clear the dialog first.
        is DialogState.LibrarySwitchConfirm -> launcherActions.applyRomDirectoryChange(ds.newRomDirectory)
        is DialogState.IntentAuditResult -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.SystemFoldersRegenerated -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.PlatformResetConfirm -> onPlatformReset(ds)
        is DialogState.ResetCustomConfigConfirm -> {
            dev.cannoli.scorza.util.DirectoryLayout.resetCustomCfg(
                dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).customCfg
            )
            nav.dialogState.value = DialogState.None
        }
        is DialogState.PermissionDetail -> {
            ds.permission.settingsAction?.let { openPermissionSettings(it) }
            nav.dialogState.value = DialogState.None
        }
        is DialogState.QuickMenu -> {
            when (ds.rows.getOrNull(ds.selectedIndex)) {
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SETTINGS ->
                    openSettings(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SETTINGS)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ROMM -> {
                    nav.dialogState.value = DialogState.None
                    nav.push(dev.cannoli.scorza.navigation.LauncherScreen.RommPlatformList())
                }
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DOWNLOADS -> nav.dialogState.value = DialogState.RommDownloads(fromQuickMenu = true)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SYNC_HISTORY -> openSyncHistory()
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS -> openConflictsMenu(fromSaveSyncMenu = false)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS -> openSyncErrors(fromSaveSyncMenu = false)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN -> launcherActions.openKitchen(fromQuickMenu = true)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.RESCAN -> launcherActions.rescanWithProgress()
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO -> {
                    nav.dialogState.value = DialogState.QuickInfo(
                        endpoints = dev.cannoli.scorza.server.KitchenManager.endpoints(hasVpn = hasActiveVpn()),
                        kitchenRunning = dev.cannoli.scorza.server.KitchenManager.isRunning,
                        pin = dev.cannoli.scorza.server.KitchenManager.pinForDisplay(),
                        romm = rommStatusFrom(rommStore.isConfigured, rommStore.host, rommStore.serverVersion),
                    )
                }
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ABOUT ->
                    nav.dialogState.value = DialogState.About(fromQuickMenu = true)
                dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DEBUG -> openSettings(
                    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DEBUG,
                    QuickSettingsCategory(SettingsCategory.DEBUG, dev.cannoli.scorza.R.string.settings_debug),
                )
                null -> nav.dialogState.value = DialogState.None
            }
        }
        is DialogState.RommDownloads -> {
            val item = rommDownloader.state.value.inDisplayOrder().getOrNull(ds.selectedIndex) ?: return true
            when (item.status) {
                is DownloadStatus.Failed -> rommDownloader.retry(item.key)
                DownloadStatus.Queued, is DownloadStatus.Downloading ->
                    nav.dialogState.value = DialogState.RommConfirm(
                        dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD,
                        downloadKey = item.key,
                        fromQuickMenu = ds.fromQuickMenu,
                    )
                else -> {}
            }
        }
        is DialogState.RommArtResults -> {
            rommArtFetcher.dismissResults()
            nav.dialogState.value = DialogState.None
        }
        is DialogState.SaveBackupRestoreConfirm -> doRestore(ds)
        is DialogState.RommConfirm -> onRommConfirm(ds)
        is DialogState.Picker -> ds.onSelect(ds.selectedIndex)
        is DialogState.RAPreloadResult -> {
            // Preload is reachable from a list as well as from the Achievements group, so this
            // asks rather than assuming: a pending return means somewhere to go back to.
            if (pendingContextReturn != null) restoreContextMenu()
            else nav.dialogState.value = DialogState.None
        }
        is DialogState.RAPreloadProgress -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.RetroAchievementsLogoutConfirm -> {
            nav.dialogState.value = DialogState.None
            onRetroAchievementsLogout?.invoke()
        }
        is DialogState.ConflictsMenu -> {}
        is DialogState.SaveSyncConflict -> onSaveConflictConfirm(ds)
        is DialogState.SaveSyncStaleBlock -> onSaveStaleConfirm(ds)
        else -> {}
    }
    return true
}

private fun DialogInputHandler.openPermissionSettings(action: String) {
    context.startActivity(
        dev.cannoli.scorza.permissions.permissionSettingsIntent(action, context)
    )
}

private data class QuickSettingsCategory(
    val key: SettingsCategory,
    @androidx.annotation.StringRes val labelRes: Int,
)

private fun DialogInputHandler.openSettings(
    row: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow,
    category: QuickSettingsCategory? = null,
) {
    nav.dialogState.value = DialogState.None
    if (nav.currentScreen is LauncherScreen.SystemList) systemListViewModel.savePosition()
    settingsViewModel.load()
    if (category != null) settingsViewModel.enterSubCategory(category.key, category.labelRes)
    nav.screenStack.add(LauncherScreen.Settings(row, category?.key))
    if (updateManager.isOnline()) {
        ioScope.launch { updateManager.checkForUpdate() }
    }
}

private fun DialogInputHandler.hasActiveVpn(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
