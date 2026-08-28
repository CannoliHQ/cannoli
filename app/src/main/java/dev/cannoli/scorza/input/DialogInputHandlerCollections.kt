package dev.cannoli.scorza.input

import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.launch

fun DialogInputHandler.onCollectionPickerConfirm(state: LauncherScreen.CollectionPicker) {
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

fun DialogInputHandler.onChildPickerConfirm(screen: LauncherScreen.ChildPicker) {
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

fun DialogInputHandler.openCollectionManager(gamePaths: List<String>, title: String) {
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

fun DialogInputHandler.openChildPicker(parentId: Long) {
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

internal fun DialogInputHandler.refreshCollectionPickerOnStack() {
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

private fun DialogInputHandler.collectionsContainingPaths(gamePaths: List<String>, candidates: List<CollectionsRepository.CollectionRow>): Set<Long> {
    if (gamePaths.isEmpty()) return emptySet()
    val sets = gamePaths.map { path ->
        val ref = resolvePathToRef(path) ?: return@map emptySet<Long>()
        val ids = collectionManager.collectionsContaining(ref)
        candidates.asSequence().map { it.id }.filter { it in ids }.toSet()
    }
    return if (gamePaths.size == 1) sets.first()
    else sets.reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
}

internal fun DialogInputHandler.addPathToCollection(collectionId: Long, path: String) {
    val ref = resolvePathToRef(path) ?: return
    collectionManager.addMember(collectionId, ref)
}

internal fun DialogInputHandler.removePathFromCollection(collectionId: Long, path: String) {
    val ref = resolvePathToRef(path) ?: return
    collectionManager.removeMember(collectionId, ref)
}

internal fun DialogInputHandler.resolvePathToRef(path: String): dev.cannoli.scorza.db.LibraryRef? {
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

internal suspend fun DialogInputHandler.clearRecentlyPlayedByPath(path: String) {
    resolvePathToRef(path)?.let { recentlyPlayedManager.clear(it) }
}
