package dev.cannoli.scorza.input

import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import dev.cannoli.ui.components.KeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun DialogInputHandler.backDialog(): Boolean {
    val ds = nav.dialogState.value
    if (ds == DialogState.None) return false
    when (ds) {
        is DialogState.KeyboardHelp -> nav.dialogState.value = ds.restore
        is DialogState.GuideHelp -> nav.dialogState.value = DialogState.None
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
            openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO)
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
        is DialogState.UninstallCoreConfirm,
        is DialogState.RemoveUnusedCoresConfirm -> nav.dialogState.value = DialogState.None
        is DialogState.UpdateCoresConfirm -> nav.dialogState.value = DialogState.None
        is DialogState.UpdateShadersConfirm -> nav.dialogState.value = DialogState.None
        is DialogState.CheckingCores -> coreUpdateController.cancel()
        is DialogState.UpdatingCores -> coreUpdateController.cancel()
        is DialogState.MissingCore,
        is DialogState.MissingApp,
        is DialogState.UnsupportedContent,
        is DialogState.MissingBios,
        is DialogState.NoEmulatorSet,
        is DialogState.LaunchError,
        is DialogState.Launching -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.UpdateDownload -> {
            updateManager.cancelDownload()
            updateManager.clearError()
            nav.dialogState.value = DialogState.About(fromQuickMenu = ds.fromQuickMenu)
        }
        is DialogState.About -> {
            if (ds.fromQuickMenu) openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ABOUT)
            else nav.dialogState.value = DialogState.None
            launcherActions.rescanSystemList()
        }
        is DialogState.Kitchen -> {
            if (ds.fromQuickMenu) {
                // Rebuild already in flight from a held Back key-repeat: swallow this
                // re-entry so rescanSystemList() below does not double-run.
                if (quickMenuRebuild.get()) return true
                openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN)
            } else {
                nav.dialogState.value = DialogState.None
            }
            launcherActions.rescanSystemList()
        }
        is DialogState.RALoggingIn -> {
            nav.dialogState.value = DialogState.None
            if (ds.failed && settingsViewModel.state.value.activeCategory != SettingsCategory.RETROACHIEVEMENTS) {
                settingsViewModel.enterSubCategory(
                    SettingsCategory.RETROACHIEVEMENTS,
                    dev.cannoli.scorza.R.string.settings_retroachievements,
                )
            }
        }
        is DialogState.RommConnected -> {
            if (ds.fromSettingsMenu) backToRommSettings(dev.cannoli.scorza.ui.components.RommSettingsRow.SERVER_INFO)
            else nav.dialogState.value = DialogState.None
        }
        is DialogState.RommPairing -> {
            rommDevicePairing.cancel()
            nav.dialogState.value = DialogState.None
        }
        is DialogState.RestartRequired -> {}
        is DialogState.LibrarySwitchConfirm -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.IntentAuditResult -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.SystemFoldersRegenerated -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.PlatformResetConfirm -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.ResetCustomConfigConfirm -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.PermissionDetail -> {
            nav.dialogState.value = DialogState.None
        }
        is DialogState.RommDownloads -> {
            if (ds.fromQuickMenu) {
                openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DOWNLOADS)
            } else {
                nav.dialogState.value = DialogState.None
            }
        }
        is DialogState.RommArtResults -> {
            rommArtFetcher.dismissResults()
            nav.dialogState.value = DialogState.None
        }
        is DialogState.Picker -> ds.onBack?.invoke() ?: run { nav.dialogState.value = DialogState.None }
        is DialogState.SyncHistory -> returnFromSaveSyncChild(
            ds.fromSaveSyncMenu,
            dev.cannoli.scorza.ui.components.RommSaveSyncRow.HISTORY,
            dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SYNC_HISTORY,
        )
        is DialogState.SyncErrors -> returnFromSaveSyncChild(
            ds.fromSaveSyncMenu,
            dev.cannoli.scorza.ui.components.RommSaveSyncRow.ERRORS,
            dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS,
        )
        is DialogState.SaveBackupRestoreConfirm -> ioScope.launch {
            val backups = saveSyncService.listBackups(ds.tag, ds.base)
            withContext(Dispatchers.Main) {
                nav.dialogState.value = saveBackupListPicker(ds.tag, ds.base, ds.displayName, backups, ds.fromContextMenu)
            }
        }
        is DialogState.ConflictsMenu -> {
            val fromSaveSyncMenu = ds.fromSaveSyncMenu
            ioScope.launch {
                val count = saveSyncService.pendingConflictCount()
                withContext(Dispatchers.Main) { showOriginMenu(fromSaveSyncMenu, count) }
            }
        }
        is DialogState.RommConfirm -> {
            when (ds.action) {
                dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE ->
                    nav.dialogState.value = rommAdvancedPicker()
                dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT ->
                    nav.dialogState.value = DialogState.RommConnected(
                        host = rommStore.host,
                        username = rommStore.username,
                        version = rommStore.serverVersion,
                    )
                dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD ->
                    nav.dialogState.value = DialogState.RommDownloads(fromQuickMenu = ds.fromQuickMenu)
                dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL ->
                    nav.dialogState.value = DialogState.RommDownloads(fromQuickMenu = ds.fromQuickMenu)
            }
        }
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
        }
        is DialogState.SaveSyncConflict -> {
            nav.dialogState.value = DialogState.None
            launcherActions.cancelPendingLaunch()
        }
        is DialogState.SaveSyncStaleBlock -> {
            nav.dialogState.value = DialogState.None
            launcherActions.cancelPendingLaunch()
        }
        is DialogState.SaveSyncChecking, is DialogState.ConflictsApplying -> {}
        else -> {}
    }
    return true
}
