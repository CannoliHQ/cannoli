package dev.cannoli.scorza.ui.screens

sealed interface RenameTarget {
    sealed interface ScopedSearch : RenameTarget {
        val scope: String
    }

    data object GameListItem : RenameTarget
    data class SystemListItem(val currentName: String) : RenameTarget
    data class ControllerMapping(val mappingId: String) : RenameTarget
    data class RaGameId(val romPath: String) : RenameTarget
    data class SaveSlotRename(val slot: String) : RenameTarget
    data object SaveSlotCreate : RenameTarget
    data object LauncherTitle : RenameTarget
    data object RaUsername : RenameTarget
    data object RaPassword : RenameTarget
    data object RommHost : RenameTarget
    data object RommPairCode : RenameTarget
    data object RommDeviceName : RenameTarget
    data class LauncherSearch(override val scope: String) : ScopedSearch
    data class RommPlatformSearch(override val scope: String) : ScopedSearch
    data class RommCollectionSearch(override val scope: String) : ScopedSearch
    data object LauncherGlobalSearch : RenameTarget
    data object RommGlobalSearch : RenameTarget
}
