package dev.cannoli.scorza.input

import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.launch

internal fun DialogInputHandler.openEmulatorPicker(rom: dev.cannoli.scorza.model.Rom) =
    openGameEmulatorPicker(rom.platformTag, rom.id, rom.displayName)

private fun DialogInputHandler.openGameEmulatorPicker(tag: String, romId: Long?, displayName: String) {
    nav.dialogState.value = DialogState.None
    nav.screenStack.add(emulatorMappingBuilder.buildPlatformMapping(
        tag = tag, platformName = platformResolver.getDisplayName(tag),
        romId = romId, gameName = displayName, selectCurrent = true,
    ))
}

// A failed launch recovers by editing whichever mapping supplied the emulator that failed:
// the per-game override when the launch came from one, the platform mapping otherwise.
internal fun DialogInputHandler.openEmulatorRecovery(platformTag: String?, romId: Long?) {
    val tag = platformTag ?: return
    pendingContextReturn = null
    if (romId == null) {
        openPlatformEmulatorPicker(tag)
        return
    }
    val selected = (gameListViewModel.getSelectedItem() as? ListItem.RomItem)?.rom
    val displayName = selected?.takeIf { it.id == romId }?.displayName
        ?: platformResolver.getDisplayName(tag)
    openGameEmulatorPicker(tag, romId, displayName)
}

private fun DialogInputHandler.openPlatformEmulatorPicker(tag: String) {
    nav.dialogState.value = DialogState.None
    nav.screenStack.add(emulatorMappingBuilder.buildPlatformMapping(
        tag = tag, platformName = platformResolver.getDisplayName(tag),
        selectCurrent = true,
    ))
}

internal fun DialogInputHandler.onPlatformReset(state: DialogState.PlatformResetConfirm) {
    platformResolver.resetPlatformToDefault(state.tag, context.packageManager)
    launcherActions.scanResumableGames()
    nav.dialogState.value = DialogState.None
    val mapping = nav.screenStack.lastOrNull() as? LauncherScreen.PlatformMapping ?: return
    nav.screenStack[nav.screenStack.lastIndex] = emulatorMappingBuilder.buildPlatformMapping(
        mapping.tag, mapping.platformName,
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

/**
 * Rebuilds the list in place after a removal, so the sizes, the totals and the footer actions
 * all describe what is on disk now. Leaving the screen as it was would keep offering to remove
 * a core that is already gone.
 */
internal fun DialogInputHandler.refreshInstalledCores() {
    if (nav.currentScreen !is LauncherScreen.InstalledCores) return
    val rebuilt = emulatorMappingBuilder.buildInstalledCores()
    val prior = nav.currentScreen as LauncherScreen.InstalledCores
    nav.replaceTop(
        rebuilt.copy(selectedIndex = prior.selectedIndex.coerceAtMost((rebuilt.rows.size - 1).coerceAtLeast(0)))
    )
}
