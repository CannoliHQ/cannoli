package dev.cannoli.scorza.input

import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.ui.components.RommSaveSyncRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun DialogInputHandler.isRommScreen(): Boolean = when (nav.currentScreen) {
    is LauncherScreen.RommPlatformList,
    is LauncherScreen.RommGameList,
    is LauncherScreen.RommGlobalSearch,
    is LauncherScreen.RommFirmwareList,
    is LauncherScreen.RommCollectionList,
    is LauncherScreen.RommCollectionGameList,
    is LauncherScreen.RommCollectionGroups,
    is LauncherScreen.RommVirtualTypes,
    is LauncherScreen.RommGameDetail -> true
    else -> false
}


internal fun DialogInputHandler.onSaveConflictConfirm(ds: DialogState.SaveSyncConflict) {
    val deviceId = saveSyncService.deviceIdOrNull() ?: run {
        nav.dialogState.value = DialogState.None
        launcherActions.proceedPendingLaunch()
        return
    }
    val keepLocal = ds.selectedIndex == 0
    ioScope.launch {
        try {
            if (keepLocal) saveSyncService.applyConflictKeepLocal(ds.conflict, deviceId)
            else saveSyncService.applyConflictUseServer(ds.conflict, deviceId)
            saveSyncService.clearResolvedConflict(ds.conflict.gameKey, ds.conflict.base, keepLocal)
            saveSyncStatusHolder.settle(
                enabled = saveSyncService.syncEnabled(),
                online = true,
                pendingConflicts = saveSyncService.pendingConflictCount(),
                hadError = false,
            )
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

internal fun DialogInputHandler.onSaveStaleConfirm(ds: DialogState.SaveSyncStaleBlock) {
    nav.dialogState.value = DialogState.None
    if (ds.selectedIndex == 0) launcherActions.proceedPendingLaunch() else launcherActions.cancelPendingLaunch()
}

internal fun DialogInputHandler.backToRommSettings(row: dev.cannoli.scorza.ui.components.RommSettingsRow) {
    nav.dialogState.value = rommSettingsPicker(
        dev.cannoli.scorza.ui.components.RommSettingsRow.entries.indexOf(row)
    )
}

/**
 * The RomM settings rows, two of which cycle a value rather than opening anything.
 *
 * Rebuilt after every cycle rather than copied with a new value: the row labels read the settings
 * they describe, so building again is what makes the row show what it just changed to.
 */
internal fun DialogInputHandler.rommSettingsPicker(selectedIndex: Int = 0): DialogState.Picker {
    val rows = dev.cannoli.scorza.ui.components.RommSettingsRow.entries.toList()
    return DialogState.Picker(
        title = context.getString(dev.cannoli.scorza.R.string.romm_settings_title),
        confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
        selectedIndex = selectedIndex,
        items = rows.map { row ->
            when (row) {
                dev.cannoli.scorza.ui.components.RommSettingsRow.CONCURRENT ->
                    dev.cannoli.scorza.ui.screens.PickerItem(
                        label = context.getString(dev.cannoli.scorza.R.string.romm_settings_concurrent),
                        value = settings.concurrentDownloads.toString(),
                        cycles = true,
                    )
                dev.cannoli.scorza.ui.components.RommSettingsRow.COVER_ART ->
                    dev.cannoli.scorza.ui.screens.PickerItem(
                        label = context.getString(dev.cannoli.scorza.R.string.romm_settings_cover_art),
                        value = context.getString(dev.cannoli.scorza.ui.components.rommArtLabelRes(rommStore.artType)),
                        cycles = true,
                    )
                else -> dev.cannoli.scorza.ui.screens.PickerItem(context.getString(row.labelRes))
            }
        },
        onCycle = { index, delta ->
            when (rows.getOrNull(index)) {
                dev.cannoli.scorza.ui.components.RommSettingsRow.CONCURRENT -> {
                    settings.concurrentDownloads = ((settings.concurrentDownloads - 1 + delta + 4) % 4) + 1
                    nav.dialogState.value = rommSettingsPicker(index)
                }
                dev.cannoli.scorza.ui.components.RommSettingsRow.COVER_ART -> {
                    val types = dev.cannoli.scorza.romm.availableArtTypes(rommStore.scanMedia)
                    val cur = types.indexOf(rommStore.artType).coerceAtLeast(0)
                    rommStore.artType = types[(cur + delta + types.size) % types.size]
                    nav.dialogState.value = rommSettingsPicker(index)
                }
                else -> {}
            }
        },
    ) { index -> onRommSettingsSelected(rows.getOrNull(index)) }
}

internal fun DialogInputHandler.rommActionsPicker(hasDownloads: Boolean): DialogState.Picker {
    val rows = dev.cannoli.scorza.ui.components.RommActionRow.visibleRows(hasDownloads)
    return DialogState.Picker(
        title = context.getString(dev.cannoli.scorza.R.string.romm_actions_title),
        confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
        items = rows.map { dev.cannoli.scorza.ui.screens.PickerItem(context.getString(it.labelRes)) },
    ) { index ->
        when (rows.getOrNull(index)) {
            dev.cannoli.scorza.ui.components.RommActionRow.DOWNLOADS -> {
                nav.dialogState.value = DialogState.RommDownloads()
            }
            else -> {}
        }
    }
}

internal fun DialogInputHandler.rommAdvancedPicker(): DialogState.Picker = DialogState.Picker(
    title = context.getString(dev.cannoli.scorza.R.string.romm_settings_advanced),
    confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
    items = dev.cannoli.scorza.ui.components.ROMM_ADVANCED_ROWS.map {
        dev.cannoli.scorza.ui.screens.PickerItem(context.getString(it))
    },
    onBack = { backToRommSettings(dev.cannoli.scorza.ui.components.RommSettingsRow.ADVANCED) },
) { index ->
    if (index == 0) {
        nav.dialogState.value = DialogState.RommConfirm(dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE)
    } else {
        val tags = romsRepository.knownPlatformTags()
        nav.dialogState.value = DialogState.None
        dev.cannoli.scorza.download.DownloadManager.ensureStarted(context)
        rommArtFetcher.start(tags)
    }
}

/** Rebuilds itself on every toggle, which is what keeps the highlight on the row that flipped. */
internal fun DialogInputHandler.rommPlatformTogglePicker(
    items: List<dev.cannoli.scorza.ui.screens.RommPlatformToggleItem>,
    selectedIndex: Int = 0,
): DialogState.Picker = DialogState.Picker(
    title = context.getString(dev.cannoli.scorza.R.string.romm_platforms_title),
    confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_toggle),
    emptyMessage = context.getString(dev.cannoli.scorza.R.string.romm_platforms_empty),
    items = items.map { dev.cannoli.scorza.ui.screens.PickerItem(it.displayName, checked = it.visible) },
    selectedIndex = selectedIndex,
    onBack = {
        ioScope.launch { rommBrowseViewModel.loadPlatforms() }
        backToRommSettings(dev.cannoli.scorza.ui.components.RommSettingsRow.PLATFORMS)
    },
) { index ->
    val item = items.getOrNull(index) ?: return@Picker
    val nowVisible = !item.visible
    val hidden = settings.hiddenRommPlatforms.toMutableSet()
    if (nowVisible) hidden.remove(item.tag) else hidden.add(item.tag)
    settings.hiddenRommPlatforms = hidden
    val newItems = items.toMutableList()
    newItems[index] = item.copy(visible = nowVisible)
    nav.dialogState.value = rommPlatformTogglePicker(newItems, index)
}

internal fun DialogInputHandler.rommCollectionTogglePicker(
    items: List<dev.cannoli.scorza.ui.screens.RommCollectionToggleItem>,
    selectedIndex: Int = 0,
): DialogState.Picker = DialogState.Picker(
    title = context.getString(dev.cannoli.scorza.R.string.romm_collections_title),
    confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_toggle),
    items = items.map { dev.cannoli.scorza.ui.screens.PickerItem(it.displayName, checked = it.visible) },
    selectedIndex = selectedIndex,
    onBack = { backToRommSettings(dev.cannoli.scorza.ui.components.RommSettingsRow.COLLECTIONS) },
) { index ->
    val item = items.getOrNull(index) ?: return@Picker
    val nowVisible = !item.visible
    when (item.group) {
        dev.cannoli.scorza.romm.RommCollectionGroup.USER -> rommStore.showUserCollections = nowVisible
        dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> rommStore.showVirtualCollections = nowVisible
        dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> rommStore.showSmartCollections = nowVisible
    }
    val newItems = items.toMutableList()
    newItems[index] = item.copy(visible = nowVisible)
    nav.dialogState.value = rommCollectionTogglePicker(newItems, index)
    if (nowVisible) {
        ioScope.launch { rommBrowseViewModel.refresh(); rommBrowseViewModel.loadCollections() }
    }
}

private fun DialogInputHandler.onRommSettingsSelected(row: dev.cannoli.scorza.ui.components.RommSettingsRow?) {
    when (row) {
        dev.cannoli.scorza.ui.components.RommSettingsRow.SERVER_INFO -> {
            nav.dialogState.value = DialogState.RommConnected(
                host = rommStore.host,
                username = rommStore.username,
                version = rommStore.serverVersion,
                fromSettingsMenu = true,
            )
        }
        dev.cannoli.scorza.ui.components.RommSettingsRow.SAVE_SYNC -> {
            ioScope.launch {
                val count = saveSyncService.pendingConflictCount()
                withContext(Dispatchers.Main) { nav.dialogState.value = buildSaveSyncMenu(pendingConflicts = count) }
            }
        }
        dev.cannoli.scorza.ui.components.RommSettingsRow.ADVANCED -> {
            nav.dialogState.value = rommAdvancedPicker()
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
            nav.dialogState.value = rommPlatformTogglePicker(items)
        }
        dev.cannoli.scorza.ui.components.RommSettingsRow.COLLECTIONS -> {
            val items = listOf(
                dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.USER, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_user), rommStore.showUserCollections),
                dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_virtual), rommStore.showVirtualCollections),
                dev.cannoli.scorza.ui.screens.RommCollectionToggleItem(dev.cannoli.scorza.romm.RommCollectionGroup.SMART, context.getString(dev.cannoli.scorza.R.string.romm_collection_group_smart), rommStore.showSmartCollections),
            )
            nav.dialogState.value = rommCollectionTogglePicker(items)
        }
        else -> {}
    }
}

/**
 * [selectedRow] rather than an index: which rows exist depends on five values this already knows,
 * so a caller working the position out for itself is a second copy of that rule.
 */
internal fun DialogInputHandler.buildSaveSyncMenu(
    pendingConflicts: Int,
    selectedRow: dev.cannoli.scorza.ui.components.RommSaveSyncRow? = null,
): DialogState.Picker {
    val supported = dev.cannoli.scorza.romm.RommCapabilities.isSupported(rommStore.serverVersion)
    val enabled = settings.rommSaveSyncEnabled
    val backupCount = settings.rommSaveBackupCount
    val syncErrors = saveSyncStatusHolder.errors.value.size
    val rows = RommSaveSyncRow.visibleRows(supported, enabled, pendingConflicts, syncErrors, saveSyncService.hasBackups())
    val selected = selectedRow?.let { rows.indexOf(it).coerceAtLeast(0) } ?: 0
    return DialogState.Picker(
        title = context.getString(dev.cannoli.scorza.R.string.setting_romm_save_sync),
        confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
        selectedIndex = selected,
        items = rows.map { row ->
            when (row) {
                RommSaveSyncRow.TOGGLE -> dev.cannoli.scorza.ui.screens.PickerItem(
                    label = context.getString(dev.cannoli.scorza.R.string.setting_romm_save_sync),
                    value = context.getString(
                        if (!supported) dev.cannoli.scorza.R.string.romm_save_sync_needs_500
                        else if (enabled) dev.cannoli.scorza.R.string.value_on
                        else dev.cannoli.scorza.R.string.value_off
                    ),
                    cycles = true,
                )
                RommSaveSyncRow.BACKUPS -> dev.cannoli.scorza.ui.screens.PickerItem(
                    label = context.getString(dev.cannoli.scorza.R.string.setting_romm_save_backups),
                    value = if (backupCount <= 0) context.getString(dev.cannoli.scorza.R.string.value_off)
                    else backupCount.toString(),
                    cycles = true,
                )
                RommSaveSyncRow.HISTORY -> dev.cannoli.scorza.ui.screens.PickerItem(
                    context.getString(dev.cannoli.scorza.R.string.sync_history_title)
                )
                RommSaveSyncRow.CONFLICTS -> dev.cannoli.scorza.ui.screens.PickerItem(
                    context.resources.getQuantityString(dev.cannoli.ui.R.plurals.quick_menu_conflicts, pendingConflicts, pendingConflicts)
                )
                RommSaveSyncRow.ERRORS -> dev.cannoli.scorza.ui.screens.PickerItem(
                    context.resources.getQuantityString(dev.cannoli.ui.R.plurals.quick_menu_sync_errors, syncErrors, syncErrors)
                )
                RommSaveSyncRow.RESTORE -> dev.cannoli.scorza.ui.screens.PickerItem(
                    context.getString(dev.cannoli.ui.R.string.save_backup_restore)
                )
            }
        },
        onBack = { backToRommSettings(dev.cannoli.scorza.ui.components.RommSettingsRow.SAVE_SYNC) },
        onCycle = { index, delta ->
            when (rows.getOrNull(index)) {
                RommSaveSyncRow.TOGGLE -> toggleSaveSync(pendingConflicts)
                RommSaveSyncRow.BACKUPS -> {
                    val options = intArrayOf(0, 3, 5, 10)
                    val idx = options.indexOf(settings.rommSaveBackupCount).let { if (it < 0) 0 else it }
                    settings.rommSaveBackupCount = options[(idx + delta).mod(options.size)]
                    nav.dialogState.value = buildSaveSyncMenu(pendingConflicts, RommSaveSyncRow.BACKUPS)
                }
                else -> {}
            }
        },
    ) { index -> onSaveSyncRowSelected(rows.getOrNull(index), pendingConflicts) }
}

private fun DialogInputHandler.toggleSaveSync(pendingConflicts: Int) {
    if (!dev.cannoli.scorza.romm.RommCapabilities.isSupported(rommStore.serverVersion)) return
    if (settings.rommSaveSyncEnabled) {
        settings.rommSaveSyncEnabled = false
        saveSyncStatusHolder.settle(enabled = false, online = true, pendingConflicts = 0, hadError = false)
        nav.dialogState.value = buildSaveSyncMenu(pendingConflicts, RommSaveSyncRow.TOGGLE)
    } else if (deviceRegistrar.isRegistered()) {
        settings.rommSaveSyncEnabled = true
        saveSyncStatusHolder.settle(enabled = true, online = true, pendingConflicts = 0, hadError = false)
        nav.dialogState.value = buildSaveSyncMenu(pendingConflicts, RommSaveSyncRow.TOGGLE)
    } else {
        val default = deviceRegistrar.defaultDeviceName()
        nav.dialogState.value = DialogState.RenameInput(
            target = RenameTarget.RommDeviceName,
            keyboard = KeyboardState(text = default, cursorPos = default.length),
        )
    }
}

private fun DialogInputHandler.onSaveSyncRowSelected(
    row: dev.cannoli.scorza.ui.components.RommSaveSyncRow?,
    pendingConflicts: Int,
) {
    when (row) {
        dev.cannoli.scorza.ui.components.RommSaveSyncRow.TOGGLE -> toggleSaveSync(pendingConflicts)
        dev.cannoli.scorza.ui.components.RommSaveSyncRow.HISTORY -> openSyncHistory(fromSaveSyncMenu = true)
        dev.cannoli.scorza.ui.components.RommSaveSyncRow.CONFLICTS -> openConflictsMenu(fromSaveSyncMenu = true)
        dev.cannoli.scorza.ui.components.RommSaveSyncRow.ERRORS -> openSyncErrors(fromSaveSyncMenu = true)
        dev.cannoli.scorza.ui.components.RommSaveSyncRow.RESTORE -> openBackupGames()
        else -> {}
    }
}

internal fun DialogInputHandler.openBackupGames() {
    ioScope.launch {
        val games = saveSyncService.listBackupGames()
        withContext(Dispatchers.Main) {
            nav.dialogState.value = DialogState.Picker(
                title = context.getString(dev.cannoli.ui.R.string.save_backup_games_title),
                confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
                emptyMessage = context.getString(dev.cannoli.ui.R.string.save_backup_none),
                items = games.map {
                    dev.cannoli.scorza.ui.screens.PickerItem(it.displayName, it.tag.uppercase())
                },
                onBack = { returnToSaveSyncMenu(dev.cannoli.scorza.ui.components.RommSaveSyncRow.RESTORE) },
            ) { index -> games.getOrNull(index)?.let { openBackupList(it) } }
        }
    }
}

internal fun DialogInputHandler.returnToSaveSyncMenu(row: dev.cannoli.scorza.ui.components.RommSaveSyncRow) {
    ioScope.launch {
        val count = saveSyncService.pendingConflictCount()
        val errorCount = saveSyncStatusHolder.errors.value.size
        withContext(Dispatchers.Main) {
            nav.dialogState.value = buildSaveSyncMenu(pendingConflicts = count, selectedRow = row)
        }
    }
}

/**
 * One game's backups. Built in three places, so where back goes is stated once here rather than
 * once per caller: the context menu route returns to that menu, everything else to the game list.
 */
internal fun DialogInputHandler.saveBackupListPicker(
    tag: String,
    base: String,
    displayName: String,
    backups: List<dev.cannoli.scorza.romm.sync.SaveBackup>,
    fromContextMenu: Boolean,
): DialogState.Picker = DialogState.Picker(
    title = displayName,
    confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
    emptyMessage = context.getString(dev.cannoli.ui.R.string.save_backup_none),
    items = backups.map {
        dev.cannoli.scorza.ui.screens.PickerItem(
            label = backupDateLabel(it.stamp),
            value = dev.cannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(it.sizeBytes),
        )
    },
    onBack = { if (fromContextMenu) openRommSavesMenu(MENU_RESTORE_BACKUP) else openBackupGames() },
) { index ->
    val backup = backups.getOrNull(index) ?: return@Picker
    nav.dialogState.value = DialogState.SaveBackupRestoreConfirm(
        tag = tag,
        base = base,
        displayName = displayName,
        stamp = backup.stamp,
        dateLabel = backupDateLabel(backup.stamp),
        fromContextMenu = fromContextMenu,
    )
}

internal fun DialogInputHandler.openBackupList(game: dev.cannoli.scorza.romm.sync.SaveBackupGame) {
    ioScope.launch {
        val backups = saveSyncService.listBackups(game.tag, game.base)
        withContext(Dispatchers.Main) {
            nav.dialogState.value = saveBackupListPicker(game.tag, game.base, game.displayName, backups, fromContextMenu = false)
        }
    }
}

// Per-game entry from the game context menu: jump straight to that game's backups.
private fun DialogInputHandler.openGameBackups(tag: String, base: String, displayName: String) {
    ioScope.launch {
        val backups = saveSyncService.listBackups(tag, base)
        withContext(Dispatchers.Main) {
            nav.dialogState.value = saveBackupListPicker(tag, base, displayName, backups, fromContextMenu = true)
        }
    }
}

internal fun DialogInputHandler.doRestore(ds: DialogState.SaveBackupRestoreConfirm) {
    ioScope.launch {
        val resolveGame = dev.cannoli.scorza.romm.sync.rommResolveGame(platformResolver, romDir()) { key ->
            romsRepository.romIdForRelativePath(key)?.let { gameOverrideStore.get(it) }
        }
        val outcome = saveSyncService.restoreBackupToHead(ds.tag, ds.base, ds.stamp, resolveGame)
        val count = saveSyncService.pendingConflictCount()
        withContext(Dispatchers.Main) {
            osdController.show(context.getString(restoreOutcomeMessage(outcome)))
            if (ds.fromContextMenu) nav.dialogState.value = DialogState.None
            else nav.dialogState.value = buildSaveSyncMenu(pendingConflicts = count)
        }
    }
}

private fun restoreOutcomeMessage(outcome: dev.cannoli.scorza.romm.sync.RestoreOutcome): Int = when (outcome) {
    dev.cannoli.scorza.romm.sync.RestoreOutcome.Promoted -> dev.cannoli.ui.R.string.save_backup_restore_synced
    dev.cannoli.scorza.romm.sync.RestoreOutcome.Escalated -> dev.cannoli.ui.R.string.save_backup_restore_conflict
    dev.cannoli.scorza.romm.sync.RestoreOutcome.PendingPromote -> dev.cannoli.ui.R.string.save_backup_restore_pending
    dev.cannoli.scorza.romm.sync.RestoreOutcome.RestoredLocalOnly -> dev.cannoli.ui.R.string.save_backup_restore_done
    dev.cannoli.scorza.romm.sync.RestoreOutcome.Failed -> dev.cannoli.ui.R.string.save_backup_restore_failed
}

private fun backupDateLabel(stamp: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))

internal fun DialogInputHandler.openSyncErrors(fromSaveSyncMenu: Boolean = false) {
    nav.dialogState.value = DialogState.SyncErrors(
        errors = saveSyncStatusHolder.errors.value,
        fromSaveSyncMenu = fromSaveSyncMenu,
    )
}

// Back out of a save-sync child list (history/errors) to whichever menu opened it,
// rebuilding it so the conflict + error rows stay consistent.
internal fun DialogInputHandler.returnFromSaveSyncChild(
    fromSaveSyncMenu: Boolean,
    saveSyncRow: dev.cannoli.scorza.ui.components.RommSaveSyncRow,
    quickRow: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow,
) {
    ioScope.launch {
        val count = saveSyncService.pendingConflictCount()
        val errorCount = saveSyncStatusHolder.errors.value.size
        if (fromSaveSyncMenu) {
            withContext(Dispatchers.Main) {
                nav.dialogState.value = buildSaveSyncMenu(pendingConflicts = count, selectedRow = saveSyncRow)
            }
        } else {
            openQuickMenu(quickRow)
        }
    }
}

internal fun DialogInputHandler.openSyncHistory(fromSaveSyncMenu: Boolean = false) {
    val nowLabel = context.getString(dev.cannoli.scorza.R.string.sync_relative_now)
    ioScope.launch {
        val entries = syncHistoryStore.recent()
        val rows = dev.cannoli.scorza.ui.screens.buildHistoryRows(entries, System.currentTimeMillis(), nowLabel)
        withContext(Dispatchers.Main) {
            nav.dialogState.value = DialogState.SyncHistory(rows, fromSaveSyncMenu = fromSaveSyncMenu)
        }
    }
}

internal fun DialogInputHandler.openConflictsMenu(fromSaveSyncMenu: Boolean = false) {
    ioScope.launch {
        val conflicts = pendingConflictStore.all()
        val rows = conflicts.map { pc ->
            val tag = pc.gameKey.substringBefore('/')
            val base = java.text.Normalizer.normalize(java.io.File(pc.gameKey).nameWithoutExtension, java.text.Normalizer.Form.NFC)
            dev.cannoli.scorza.ui.screens.ConflictRow(
                gameKey = pc.gameKey,
                name = pc.displayName,
                localMillis = saveSyncService.localSaveModifiedMillis(tag, base),
                serverMillis = pc.serverUpdatedAt?.let(::isoToMillis),
            )
        }
        withContext(Dispatchers.Main) {
            nav.dialogState.value = DialogState.ConflictsMenu(rows = rows, fromSaveSyncMenu = fromSaveSyncMenu)
        }
    }
}

private fun isoToMillis(iso: String): Long? = try {
    java.time.Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) { null }
}

internal fun DialogInputHandler.cycleConflictChoice(ds: DialogState.ConflictsMenu, delta: Int) {
    val row = ds.rows.getOrNull(ds.selectedIndex) ?: return
    val choices = dev.cannoli.scorza.ui.screens.ConflictChoice.entries
    val next = choices[(row.choice.ordinal + delta).mod(choices.size)]
    val newRows = ds.rows.toMutableList()
    newRows[ds.selectedIndex] = row.copy(choice = next)
    nav.dialogState.value = ds.copy(rows = newRows)
}

// Each pass downloads or uploads a save per row and takes seconds. The applying state replaces
// the list so the press has visible feedback and the rows can't be re-applied mid-flight; the
// guard is the second line of defense on the async boundary.
internal fun DialogInputHandler.applyAllConflicts(ds: DialogState.ConflictsMenu) {
    if (!applyingConflicts.compareAndSet(false, true)) return
    val fromSaveSyncMenu = ds.fromSaveSyncMenu
    val rows = ds.rows
    val resolveGame = dev.cannoli.scorza.romm.sync.rommResolveGame(platformResolver, romDir()) { key ->
            romsRepository.romIdForRelativePath(key)?.let { gameOverrideStore.get(it) }
        }
    nav.dialogState.value = DialogState.ConflictsApplying
    ioScope.launch {
        try {
            var failed = 0
            for (row in rows) {
                val applied = when (row.choice) {
                    dev.cannoli.scorza.ui.screens.ConflictChoice.KEEP_LOCAL ->
                        saveSyncService.resolvePending(row.gameKey, keepLocal = true, resolveGame)
                    dev.cannoli.scorza.ui.screens.ConflictChoice.USE_SERVER ->
                        saveSyncService.resolvePending(row.gameKey, keepLocal = false, resolveGame)
                    dev.cannoli.scorza.ui.screens.ConflictChoice.SKIP -> {
                        saveSyncService.skipPending(row.gameKey)
                        true
                    }
                }
                if (!applied) failed++
            }
            val count = saveSyncService.pendingConflictCount()
            saveSyncStatusHolder.settle(enabled = saveSyncService.syncEnabled(), online = true, pendingConflicts = count, hadError = failed > 0)
            withContext(Dispatchers.Main) {
                if (failed > 0) {
                    osdController.show(
                        context.resources.getQuantityString(
                            dev.cannoli.ui.R.plurals.conflicts_apply_failed, failed, failed
                        )
                    )
                }
                showOriginMenu(fromSaveSyncMenu, count)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Never strand the user on the applying overlay: it is full screen and eats input.
            withContext(Dispatchers.Main) { nav.dialogState.value = DialogState.None }
        } finally {
            applyingConflicts.set(false)
        }
    }
}

internal fun DialogInputHandler.showOriginMenu(fromSaveSyncMenu: Boolean, count: Int) {
    if (fromSaveSyncMenu) {
        nav.dialogState.value = buildSaveSyncMenu(
            pendingConflicts = count,
            selectedRow = dev.cannoli.scorza.ui.components.RommSaveSyncRow.CONFLICTS,
        )
    } else {
        openQuickMenu(dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS)
    }
}

internal fun DialogInputHandler.onRommConfirm(ds: DialogState.RommConfirm) {
    when (ds.action) {
        dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE -> {
            nav.dialogState.value = DialogState.None
            ioScope.launch { rommBrowseViewModel.rebuild() }
        }
        dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT -> {
            rommStore.disconnect()
            saveSyncStatusHolder.settle(enabled = saveSyncService.syncEnabled(), online = true, pendingConflicts = 0, hadError = false)
            settingsViewModel.load()
            nav.dialogState.value = DialogState.None
            while (isRommScreen()) nav.pop()
        }
        dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD -> {
            ds.downloadKey?.let { rommDownloader.cancel(it) }
            nav.dialogState.value = DialogState.RommDownloads(fromQuickMenu = ds.fromQuickMenu)
        }
        dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL -> {
            rommDownloader.cancelAll()
            nav.dialogState.value = DialogState.RommDownloads(fromQuickMenu = ds.fromQuickMenu)
        }
    }
}

/**
 * The versions of one RomM game, and downloading the chosen one.
 *
 * Formatting lives here rather than in the picker: the glyph marking the primary variant and the
 * size beside each row are this menu's business, and a row type that knew about either would have
 * to learn every other menu's decoration too.
 */
internal fun DialogInputHandler.rommVersionPicker(
    tag: String,
    entries: List<dev.cannoli.scorza.ui.screens.RommVariantEntry>,
): DialogState.Picker = DialogState.Picker(
    title = context.getString(dev.cannoli.scorza.R.string.romm_version_picker_title),
    confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_download),
    items = entries.map { entry ->
        dev.cannoli.scorza.ui.screens.PickerItem(
            label = if (entry.isPrimary) "${dev.cannoli.ui.theme.CannoliIcons.Primary.glyph} ${entry.label}" else entry.label,
            value = dev.cannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(entry.game.sizeBytes),
            dot = if (entry.present) true else null,
        )
    },
) { index ->
    val entry = entries.getOrNull(index) ?: return@Picker
    nav.dialogState.value = DialogState.None
    rommDownloader.enqueue(listOf(dev.cannoli.scorza.romm.download.rommItem(entry.game, tag, dev.cannoli.scorza.download.DownloadKind.ROM)))
    dev.cannoli.scorza.download.DownloadManager.ensureStarted(context)
    osdController.show(context.getString(dev.cannoli.ui.R.string.romm_osd_download_queued))
}

internal fun DialogInputHandler.artResultRowCount(ds: DialogState.RommArtResults): Int =
    dev.cannoli.scorza.ui.screens.rommArtIssueRows(
        ds.results,
        context.getString(dev.cannoli.ui.R.string.romm_art_section_no_match),
        context.getString(dev.cannoli.ui.R.string.romm_art_section_failed),
    ).size

internal fun DialogInputHandler.rommSavesOptions(rom: dev.cannoli.scorza.model.Rom): List<String> = buildList {
    val gameKey = RomKeys.relativeKey(rom.path, romDir())
    val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
    if (saveSyncService.isSyncableGame(gameKey) != null) add(MENU_SAVE_SLOTS)
    if (saveSyncService.listBackups(rom.platformTag, base).isNotEmpty()) add(MENU_RESTORE_BACKUP)
}

fun DialogInputHandler.openRommSavesMenu(selectRow: String? = null) {
    val rom = (gameListViewModel.getSelectedItem() as? ListItem.RomItem)?.rom ?: run {
        nav.dialogState.value = DialogState.None; return
    }
    val options = rommSavesOptions(rom)
    if (options.isEmpty()) { nav.dialogState.value = DialogState.None; return }
    val idx = selectRow?.let { options.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    nav.dialogState.value = DialogState.Picker(
        title = MENU_ROMM_SAVES,
        confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
        items = options.map { dev.cannoli.scorza.ui.screens.PickerItem(it) },
        selectedIndex = idx,
        onBack = { restoreContextMenu() },
    ) { index ->
        when (options.getOrNull(index)) {
            MENU_SAVE_SLOTS -> openSaveSlotsForRom(rom)
            MENU_RESTORE_BACKUP -> {
                val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
                openGameBackups(rom.platformTag, base, rom.displayName)
            }
        }
    }
}


private fun DialogInputHandler.openSaveSlotsForRom(rom: dev.cannoli.scorza.model.Rom) {
    val gameKey = RomKeys.relativeKey(rom.path, romDir())
    val romId = saveSyncService.isSyncableGame(gameKey) ?: return
    val tag = rom.platformTag
    val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
    val emulator = RomKeys.coreDisplayNameFor(rom, platformResolver, gameOverrideStore.get(rom.id))
    ioScope.launch {
        val slots = runCatching { slotManager.listSlots(gameKey, romId) }.onFailure { e ->
            ErrorLog.write("save_slots_open: ${e.message}")
        }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) {
            // Push the screen and drop the submenu together so no frame shows the list in between.
            nav.push(LauncherScreen.SaveSlots(gameKey, tag, base, rom.displayName, romId, emulator, slots))
            nav.dialogState.value = DialogState.None
        }
    }
}
