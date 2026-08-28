package dev.cannoli.scorza.input

import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.artTag
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.romm.download.sanitizeFsName
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.scorza.util.AtomicRename
import dev.cannoli.scorza.util.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun DialogInputHandler.dispatchKeyboardConfirm(ds: KeyboardHost) {
    when (ds) {
        is DialogState.RenameInput -> onRenameConfirm(ds)
        is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
        is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
        is DialogState.NewFolderInput -> onNewFolderConfirm(ds)
        else -> {}
    }
}

private fun DialogInputHandler.onNewCollectionConfirm(state: DialogState.NewCollectionInput) {
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

private fun DialogInputHandler.onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
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

private fun DialogInputHandler.onNewFolderConfirm(state: DialogState.NewFolderInput) {
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

private fun DialogInputHandler.onRenameConfirm(state: DialogState.RenameInput) {
    when (val target = state.target) {
        is RenameTarget.ControllerMapping -> {
            val newName = state.currentName.trim()
            val vm = controllersViewModel
            val mapping = vm.state.value.connected.firstOrNull { it.mapping.id == target.mappingId }?.mapping
                ?: vm.state.value.savedMappings.firstOrNull { it.id == target.mappingId }
            if (mapping != null && newName.isNotEmpty() && newName != mapping.displayName) {
                vm.renameMapping(mapping, newName)
            }
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RaUsername -> {
            settings.raUsername = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RaPassword -> {
            settingsViewModel.raPassword = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RommHost -> {
            rommStore.host = state.currentName.trim()
            settingsViewModel.refreshSubList()
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RommPairCode -> {
            val code = state.currentName
            nav.dialogState.value = DialogState.None
            activityActions.startRommCodePairing(rommStore.host, code)
        }
        is RenameTarget.RommDeviceName -> {
            val name = state.currentName.trim().ifEmpty { deviceRegistrar.defaultDeviceName() }
            nav.dialogState.value = DialogState.None
            ioScope.launch {
                runCatching { deviceRegistrar.register(name) }
                    .onSuccess {
                        settings.rommSaveSyncEnabled = true
                        val count = saveSyncService.pendingConflictCount()
                        saveSyncStatusHolder.settle(enabled = true, online = true, pendingConflicts = count, hadError = false)
                        withContext(Dispatchers.Main) { nav.dialogState.value = buildSaveSyncMenu(pendingConflicts = count) }
                    }
                    .onFailure { ErrorLog.write("romm device registration failed: ${it.message}") }
            }
        }
        is RenameTarget.RommPlatformSearch -> {
            (nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.RommGameList)?.let {
                nav.replaceTop(it.copy(search = state.currentName.trim(), selectedIndex = 0, scrollTarget = 0))
            }
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RommCollectionSearch -> {
            (nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.RommCollectionGameList)?.let {
                nav.replaceTop(it.copy(search = state.currentName.trim(), selectedIndex = 0, scrollTarget = 0))
            }
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RommGlobalSearch -> {
            val term = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (term.isNotBlank()) nav.push(LauncherScreen.RommGlobalSearch(term = term))
        }
        is RenameTarget.LauncherSearch -> {
            val term = state.currentName.trim()
            if (term.isBlank()) gameListViewModel.clearSearch() else gameListViewModel.setSearch(term)
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.LauncherGlobalSearch -> {
            if (nav.navigating) return
            val term = state.currentName.trim()
            if (term.isBlank()) {
                nav.dialogState.value = DialogState.None
                return
            }
            // Keep the keyboard up until results are ready so the screen underneath never flashes,
            // then dismiss it and reveal the populated results in the same frame.
            nav.navigating = true
            gameListViewModel.loadGlobalSearch(dev.cannoli.scorza.model.GameSearchQuery(term)) {
                launcherActions.scanResumableGames()
                nav.screenStack.add(LauncherScreen.GameList)
                nav.dialogState.value = DialogState.None
                nav.navigating = false
            }
        }
        is RenameTarget.SaveSlotCreate -> {
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
        }
        is RenameTarget.SaveSlotRename -> {
            val newSlot = state.currentName.trim()
            nav.dialogState.value = DialogState.None
            if (newSlot.isNotBlank() && newSlot != target.slot) {
                val s = nav.currentScreen as? dev.cannoli.scorza.navigation.LauncherScreen.SaveSlots ?: return
                ioScope.launch {
                    runCatching { slotManager.rename(s.gameKey, s.tag, s.base, s.romId, s.emulator, target.slot, newSlot) }
                        .onFailure { ErrorLog.write("save slot rename failed: ${it.message}") }
                    withContext(Dispatchers.Main) { saveSlotsHandler.refreshSlots() }
                }
            }
        }
        is RenameTarget.LauncherTitle -> {
            settings.title = state.currentName.trim()
            settingsViewModel.refreshSubList()
            settingsViewModel.load()
            nav.dialogState.value = DialogState.None
        }
        is RenameTarget.RaGameId -> {
            val gameId = state.currentName.trim().toIntOrNull()
            ioScope.launch {
                romsRepository.gameByPath(target.romPath)?.let { romsRepository.setRaGameId(it.id, gameId) }
                gameListViewModel.reload()
            }
            restoreContextMenu()
        }
        is RenameTarget.SystemListItem -> launcherActions.handleSystemListRename(target.currentName, state.currentName.trim())
        is RenameTarget.GameListItem -> renameSelectedGameListItem(state.currentName.trim())
    }
}

private fun DialogInputHandler.renameSelectedGameListItem(newName: String) {
    val item = gameListViewModel.getSelectedItem() ?: return
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
            artworkLookup.renameArt(item.app.type.artTag, sanitizeFsName(currentName), sanitizeFsName(newName))
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
                val newPrimary = result.newPrimary
                val newRelative = newPrimary?.let { relativeRomPath(it) }
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
