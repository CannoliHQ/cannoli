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
        selected == MENU_ACHIEVEMENTS -> {
            if (rom == null) return
            openAchievementsMenu(rom.id)
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
 * Clears a manual RA Game ID, putting the game back on hash detection.
 *
 * Gated on the row's own [PickerItem.clears] flag rather than on its title or label, so the button
 * does exactly what the legend beside it offered and nothing else. A picker that quietly cleared
 * whatever happened to be selected would be worse than one that ignored the button.
 */
internal fun DialogInputHandler.clearRaGameId(romId: Long) {
    val rom = romForAchievements(romId) ?: return
    if (effectiveRaGameId(rom) == null) return
    ioScope.launch {
        romsRepository.setRaGameId(rom.id, null)
        // The recorded cache id came from the override, so it goes with it. Left behind, the row
        // would keep reading Cached off a set filed under an identity this game no longer claims.
        // The files stay on the card; only this rom's claim on them is dropped.
        romsRepository.setRaCachedGameId(rom.id, null)
        gameListViewModel.reload {
            launcherActions.scanResumableGames()
            openAchievementsMenu(romId, selectRow = MENU_RA_GAME_ID)
        }
    }
}

/** A return to this group, keeping whatever menu was underneath so it survives one round trip. */
private fun DialogInputHandler.returnToGroup(romId: Long, row: String) = ContextReturn.Achievements(
    romId = romId,
    selectRow = row,
    parent = pendingContextReturn as? ContextReturn.Single,
)

internal fun DialogInputHandler.openRaGameIdInput(rom: dev.cannoli.scorza.model.Rom) {
    val current = rom.raGameId?.toString() ?: ""
    nav.dialogState.value = DialogState.RenameInput(
        target = RenameTarget.RaGameId(rom.path.absolutePath),
        keyboard = KeyboardState(text = current, cursorPos = current.length, layout = KeyboardLayout.Number),
    )
}

internal fun DialogInputHandler.preloadAchievementsFor(rom: dev.cannoli.scorza.model.Rom) {
    // A successful preload writes raCachedGameId, which is what the row reads to say Cached. The
    // list has to be rebuilt from that or the group reopens holding the rom as it was before, and
    // a game that cached perfectly well still reads uncached.
    raPreloadController.preloadRom(rom) { gameListViewModel.reload() }
}

/**
 * The rom this group is bound to, looked up fresh by id.
 *
 * Never the current selection: a reload can move it, because the list restores a position by index
 * when the id it preserved no longer matches. The group would then rebind to a different game while
 * looking identical, and act on it.
 */
internal fun DialogInputHandler.romForAchievements(romId: Long): dev.cannoli.scorza.model.Rom? =
    gameListViewModel.state.value.items
        .filterIsInstance<ListItem.RomItem>()
        .firstOrNull { it.rom.id == romId }
        ?.rom

/**
 * The game this ROM's achievements live under: the id you gave it, else the one a preload recorded.
 *
 * Both name the same directory in the offline store, so either is enough to find a cached set. Only
 * the recorded one used to be consulted, which made a game cached under a manual override read as
 * uncached even with that override sitting in the row above.
 */
internal fun effectiveRaGameId(rom: dev.cannoli.scorza.model.Rom): Int? =
    rom.raGameId ?: rom.raCachedGameId

/** The rows inside Achievements, formatted with their values, in a fixed order. */
internal fun DialogInputHandler.achievementsOptions(rom: dev.cannoli.scorza.model.Rom): List<String> =
    buildList {
        add("$MENU_RA_GAME_ID\t${rom.raGameId?.toString() ?: "Autodetect"}")
        if (dev.cannoli.scorza.achievements.RaPreloadEligibility.isEligible(
                platformTag = rom.platformTag,
                raLoggedIn = settings.raToken.isNotEmpty(),
            )
        ) {
            val cached = effectiveRaGameId(rom)?.let { gid ->
                dev.cannoli.scorza.achievements.RaOfflineStore(
                    dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).configRaOffline
                ).isCached(gid)
            } ?: false
            add(if (cached) "$MENU_PRELOAD_ACHIEVEMENTS\tCached" else MENU_PRELOAD_ACHIEVEMENTS)
        }
        add("$MENU_ACHIEVEMENTS_MODE\t${achievementsModeValue(rom)}")
    }

/** What the mode row shows: what this game states, or the global it defers to, or the ID's ruling. */
internal fun DialogInputHandler.achievementsModeValue(rom: dev.cannoli.scorza.model.Rom): String = when {
    rom.raGameId != null -> context.getString(dev.cannoli.ui.R.string.achievements_mode_locked)
    rom.raHardcore == true -> context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore)
    rom.raHardcore == false -> context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore)
    // Names the mode it defers to, so choosing it is not a guess about what the global says.
    else -> context.getString(
        dev.cannoli.ui.R.string.achievos_mode_use_global,
        context.getString(
            if (settings.raHardcore) dev.cannoli.ui.R.string.achievos_mode_hardcore
            else dev.cannoli.ui.R.string.achievos_mode_softcore
        ),
    )
}

/**
 * The Achievements group, opened from the game context menu.
 *
 * A Picker rather than a nested context menu, matching RomM Saves: same shape, and its onBack
 * already returns to the menu that opened it. [selectRow] keeps the cursor where it was when a
 * row rebuilds itself after changing.
 */
internal fun DialogInputHandler.openAchievementsMenu(romId: Long, selectRow: String? = null) {
    val rom = romForAchievements(romId) ?: run {
        nav.dialogState.value = DialogState.None; return
    }
    val options = achievementsOptions(rom)
    val idx = selectRow
        ?.let { key -> options.indexOfFirst { it.substringBefore('\t') == key } }
        ?.takeIf { it >= 0 } ?: 0
    // A Game ID settles the mode, so the row it settles stops offering to change.
    val modeCycles = rom.raGameId == null
    nav.dialogState.value = DialogState.Picker(
        title = MENU_ACHIEVEMENTS,
        confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
        items = options.map { opt ->
            val key = opt.substringBefore('\t')
            dev.cannoli.scorza.ui.screens.PickerItem(
                label = MENU_LABELS[key]?.let { context.getString(it) } ?: key,
                value = opt.substringAfter('\t', "").takeIf { it.isNotEmpty() },
                cycles = key == MENU_ACHIEVEMENTS_MODE && modeCycles,
                // Any id the row is standing on, whether you set it or a preload recorded it.
                // Keying this on the override alone stranded a recorded id: with no override to
                // clear, the row kept reporting a cache nothing could give up.
                clears = key == MENU_RA_GAME_ID && effectiveRaGameId(rom) != null,
            )
        },
        selectedIndex = idx,
        onBack = { restoreContextMenu() },
        // Only the mode row declares cycles, so this is only ever called for it.
        onCycle = { _, direction -> cycleAchievementsMode(romId, direction) },
        onNorth = { clearRaGameId(romId) },
    ) { index ->
        when (options.getOrNull(index)?.substringBefore('\t')) {
            MENU_RA_GAME_ID -> {
                pendingContextReturn = returnToGroup(romId, MENU_RA_GAME_ID)
                openRaGameIdInput(rom)
            }
            MENU_PRELOAD_ACHIEVEMENTS -> {
                pendingContextReturn = returnToGroup(romId, MENU_PRELOAD_ACHIEVEMENTS)
                preloadAchievementsFor(rom)
            }
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
internal fun DialogInputHandler.cycleAchievementsMode(romId: Long, direction: Int) {
    val rom = romForAchievements(romId) ?: return
    if (rom.raGameId != null) return

    val order = listOf(null, true, false)
    val next = order[(order.indexOf(rom.raHardcore) + direction).mod(order.size)]
    ioScope.launch {
        romsRepository.setRaHardcore(rom.id, next)
        gameListViewModel.reload {
            launcherActions.scanResumableGames()
            // Reopened rather than restored, keeping the cursor on the row that changed.
            // restoreContextMenu answers a return only the confirm path records on its way out to
            // another screen; cycling never leaves, so it would close the menu instead.
            openAchievementsMenu(romId, selectRow = MENU_ACHIEVEMENTS_MODE)
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
            // Everything RetroAchievements is meaningless logged out: an id identifies a game to
            // an account that is not connected, and a mode is a property of a session that does
            // not exist. The whole group goes rather than reading as rows waiting on a login.
            val options =
                if (settings.raToken.isNotEmpty()) gameContextOptions
                else gameContextOptions.filterNot { it == MENU_ACHIEVEMENTS }
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
                    else -> menuItem
                }
            })
            if (rom?.artFile != null) {
                val idx = indexOf(MENU_DELETE_GAME)
                if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
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
        is ContextReturn.Achievements -> {
            // Consumed: put the menu underneath back, so the group's own back leaves it.
            pendingContextReturn = ret.parent
            openAchievementsMenu(ret.romId, ret.selectRow)
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
