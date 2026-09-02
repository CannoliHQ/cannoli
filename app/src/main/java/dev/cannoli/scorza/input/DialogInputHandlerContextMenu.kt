package dev.cannoli.scorza.input

import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.artTag
import dev.cannoli.scorza.model.VirtualPlatformTags
import dev.cannoli.scorza.model.recentKey
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.romm.download.sanitizeFsName
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun DialogInputHandler.onContextMenuConfirm(state: DialogState.ContextMenu) {
    if (nav.currentScreen == LauncherScreen.SystemList) {
        val fghItem = nav.pendingFghItem
        if (fghItem != null) {
            nav.pendingFghItem = null
            onFghContextMenuConfirm(fghItem, state)
        } else {
            when (state.options[state.selectedOption]) {
                MENU_RENAME -> {
                    val renameTitle = when (systemListViewModel.getSelectedItem()) {
                        is SystemListViewModel.ListItem.PlatformItem -> dev.cannoli.ui.R.string.keyboard_title_rename_platform
                        is SystemListViewModel.ListItem.CollectionItem -> dev.cannoli.ui.R.string.keyboard_title_rename_collection
                        else -> dev.cannoli.ui.R.string.keyboard_title_rename_folder
                    }
                    nav.dialogState.value = DialogState.RenameInput(
                        target = RenameTarget.SystemListItem(state.gameName),
                        titleRes = renameTitle,
                        keyboard = KeyboardState(text = state.gameName, cursorPos = state.gameName.length),
                    )
                }
                MENU_DOWNLOAD_ART -> {
                    val tag = systemListViewModel.getSelectedPlatformTag()
                    nav.dialogState.value = DialogState.None
                    if (tag != null) {
                        dev.cannoli.scorza.download.DownloadManager.ensureStarted(context)
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
                val renameTitle = when (item) {
                    is ListItem.AppItem -> dev.cannoli.ui.R.string.keyboard_title_rename_app
                    is ListItem.SubfolderItem -> dev.cannoli.ui.R.string.keyboard_title_rename_folder
                    else -> dev.cannoli.ui.R.string.keyboard_title_rename_game
                }
                nav.dialogState.value = DialogState.RenameInput(
                    target = RenameTarget.GameListItem,
                    titleRes = renameTitle,
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
            pendingContextReturn = null
            if (rom != null) {
                rom.artFile?.delete()
                scanner.markLauncherMutation(rom.platformTag)
            } else if (app != null) {
                artworkLookup.deleteArt(app.type.artTag, sanitizeFsName(app.displayName))
            }
            gameListViewModel.reload()
            nav.dialogState.value = DialogState.None
        }
        selected == MENU_RA_GAME_ID || selected.startsWith("$MENU_RA_GAME_ID\t") -> {
            if (rom != null) {
                val current = rom.raGameId?.toString() ?: ""
                nav.dialogState.value = DialogState.RenameInput(
                    target = RenameTarget.RaGameId(rom.path.absolutePath),
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
        selected == MENU_ROMM_SAVES -> {
            if (rom == null) return
            openRommSavesMenu()
        }
        selected == MENU_GUIDES -> {
            if (rom == null) return
            openGuides?.invoke(rom)
        }
    }
}

/**
 * Steps this game's achievements mode, on Left and Right like every other value Cannoli shows.
 *
 * Three answers rather than two: null is the game deferring to the global mode, which has to stay
 * reachable or a game could never stop overriding it. A manual Game ID settles the mode on its own,
 * so the row is inert until that is cleared.
 */
internal fun DialogInputHandler.cycleAchievementsMode(state: DialogState.ContextMenu, direction: Int) {
    val selected = state.options.getOrNull(state.selectedOption) ?: return
    if (selected.substringBefore('\t') != MENU_ACHIEVEMENTS_MODE) return
    val rom = (gameListViewModel.getSelectedItem() as? ListItem.RomItem)?.rom ?: return
    if (rom.raGameId != null) return

    val order = listOf(null, true, false)
    val next = order[(order.indexOf(rom.raHardcore) + direction).mod(order.size)]
    ioScope.launch {
        romsRepository.setRaHardcore(rom.id, next)
        gameListViewModel.reload {
            launcherActions.scanResumableGames()
            // Rebuilt in place, not restored. restoreContextMenu answers a return that only the
            // confirm path records on its way out to another screen; cycling never leaves, so it
            // would find nothing pending and close the menu instead of redrawing it.
            val item = gameListViewModel.getSelectedItem() ?: return@reload
            nav.dialogState.value = state.copy(
                options = buildGameContextOptions(item, gameListViewModel.state.value),
            )
        }
    }
}

internal fun DialogInputHandler.onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
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
            gameListViewModel.state.value.items.forEach { item ->
                when {
                    item is ListItem.RomItem && item.rom.path.absolutePath in pathSet -> {
                        item.rom.artFile?.delete()
                        scanner.markLauncherMutation(item.rom.platformTag)
                    }
                    item is ListItem.AppItem && item.recentKey() in pathSet -> {
                        artworkLookup.deleteArt(item.app.type.artTag, sanitizeFsName(item.app.displayName))
                    }
                }
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
                        dev.cannoli.scorza.achievements.RaPreloadEligibility.isEligible(
                            platformTag = rom.platformTag,
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

internal fun DialogInputHandler.onDeleteConfirm(state: DialogState.DeleteConfirm) {
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

fun DialogInputHandler.buildGameContextOptions(item: ListItem, glState: GameListViewModel.State): List<String> {
    if (glState.isCollectionsList || item is ListItem.ChildCollectionItem) return listOf(MENU_RENAME, MENU_CHILD_COLLECTIONS, MENU_DELETE)
    if (item is ListItem.SubfolderItem) return listOf(MENU_RENAME, MENU_DELETE)
    val rom = (item as? ListItem.RomItem)?.rom
    val app = (item as? ListItem.AppItem)?.app
    val isApk = app != null
    val platformTag = rom?.platformTag ?: (if (app?.type == AppType.TOOL) VirtualPlatformTags.TOOLS else VirtualPlatformTags.PORTS)
    val romPath = rom?.path?.absolutePath
    val isFav = when {
        rom != null -> rom.id in glState.favoriteRomIds
        app != null -> app.id in glState.favoriteAppIds
        else -> false
    } || (glState.isCollection && glState.isFavorites)
    return buildList {
        if (glState.platformTag == VirtualPlatformTags.RECENTLY_PLAYED) add(MENU_REMOVE_FROM_RECENTS)
        add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
        if (isApk) {
            add(MENU_MANAGE_COLLECTIONS)
            add(MENU_RENAME)
            if (app?.artFile != null) add(MENU_DELETE_ART)
            add(MENU_REMOVE)
        } else {
            // Both RetroAchievements rows are meaningless logged out: a game id identifies a
            // game to an account that is not connected, and softcore is a property of a session
            // that does not exist. They read as settings that do nothing rather than as rows
            // waiting on a login.
            val raRows = setOf(MENU_RA_GAME_ID, MENU_ACHIEVEMENTS_MODE)
            val options =
                if (settings.raToken.isNotEmpty()) gameContextOptions
                else gameContextOptions.filterNot { it in raRows }
            addAll(options.map { menuItem ->
                when {
                    menuItem == MENU_EMULATOR_OVERRIDE && rom != null -> {
                        // Reads the stored choice directly. Matching it back against a
                        // generated option list used to blank this row whenever the option
                        // could no longer be produced, e.g. an undownloaded Ricotta core.
                        val desc = gameOverrideStore.get(rom.id)?.let {
                            platformResolver.describeChoice(it, context.packageManager)
                        } ?: context.getString(dev.cannoli.scorza.R.string.emulator_platform_default)
                        "$MENU_EMULATOR_OVERRIDE\t$desc"
                    }
                    menuItem == MENU_RA_GAME_ID -> "$MENU_RA_GAME_ID\t${rom?.raGameId?.toString() ?: "Autodetect"}"
                    menuItem == MENU_ACHIEVEMENTS_MODE -> {
                        val value = when {
                            rom?.raGameId != null -> context.getString(dev.cannoli.ui.R.string.achievements_mode_locked)
                            rom?.raHardcore == true -> context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore)
                            rom?.raHardcore == false -> context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore)
                            // Names the mode it defers to, so choosing it is not a guess about
                            // what the global currently says.
                            else -> context.getString(
                                dev.cannoli.ui.R.string.achievos_mode_use_global,
                                context.getString(
                                    if (settings.raHardcore) dev.cannoli.ui.R.string.achievos_mode_hardcore
                                    else dev.cannoli.ui.R.string.achievos_mode_softcore
                                ),
                            )
                        }
                        "$MENU_ACHIEVEMENTS_MODE\t$value"
                    }
                    else -> menuItem
                }
            })
            if (rom?.artFile != null) {
                val idx = indexOf(MENU_DELETE_GAME)
                if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
            }
            if (rom != null && dev.cannoli.scorza.achievements.RaPreloadEligibility.isEligible(
                    platformTag = rom.platformTag,
                    raLoggedIn = settings.raToken.isNotEmpty(),
                )
            ) {
                val cached = rom.raCachedGameId?.let { gid ->
                    dev.cannoli.scorza.achievements.RaOfflineStore(
                        dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).configRaOffline
                    ).isCached(gid)
                } ?: false
                val item = if (cached) "$MENU_PRELOAD_ACHIEVEMENTS\tCached" else MENU_PRELOAD_ACHIEVEMENTS
                val raIdx = indexOfFirst { it == MENU_RA_GAME_ID || it.startsWith("$MENU_RA_GAME_ID\t") }
                if (raIdx >= 0) add(raIdx + 1, item) else add(item)
            }
            if (item is ListItem.RomItem && rommSavesOptions(item.rom).isNotEmpty()) {
                val idx = indexOf(MENU_RENAME)
                if (idx >= 0) add(idx, MENU_ROMM_SAVES) else add(MENU_ROMM_SAVES)
            }
            if (item is ListItem.RomItem &&
                dev.cannoli.igm.GuideManager(
                    settings.sdCardRoot, item.rom.platformTag, dev.cannoli.core.RomKey.baseName(item.rom.path)
                ).findGuides().isNotEmpty()
            ) {
                val idx = indexOfFirst { it == MENU_EMULATOR_OVERRIDE || it.startsWith("$MENU_EMULATOR_OVERRIDE\t") }
                if (idx >= 0) add(idx, MENU_GUIDES) else add(MENU_GUIDES)
            }
        }
    }
}

fun DialogInputHandler.restoreContextMenu() {
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

private fun DialogInputHandler.onFghContextMenuConfirm(item: ListItem, state: DialogState.ContextMenu) {
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

private fun DialogInputHandler.deleteRom(rom: dev.cannoli.scorza.model.Rom) {
    deleteRomFiles(rom)
    romsRepository.deleteRom(rom.id)
    scanner.markLauncherMutation(rom.platformTag)
}

private fun DialogInputHandler.deleteRomFiles(rom: dev.cannoli.scorza.model.Rom) {
    val romFile = rom.path
    romDirectoryWalker.arcadeSupportDir(romFile, platformResolver.isArcade(rom.platformTag))
        ?.deleteRecursively()
    val gameDir = romDirectoryWalker.gameDirectory(romFile)
    when {
        // gameDir covers every organizer bundle shape (m3u, cue, disc-group, stem-matching rom folder).
        gameDir != null -> gameDir.deleteRecursively()
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
