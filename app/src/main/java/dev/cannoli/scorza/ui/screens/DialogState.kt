package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardState

enum class EmulatorMappingStatus { READY, NOT_INSTALLED, NEEDS_SETUP }
data class EmulatorMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String, val status: EmulatorMappingStatus = EmulatorMappingStatus.READY, val group: String? = null)

sealed interface MappingListRow {
    val isSelectable: Boolean
    data class Group(val label: String) : MappingListRow { override val isSelectable = false }
    data class Platform(val entry: EmulatorMappingEntry) : MappingListRow { override val isSelectable = true }
}

fun groupMappingRows(entries: List<EmulatorMappingEntry>): List<MappingListRow> {
    val rows = mutableListOf<MappingListRow>()
    var current: String? = null
    for (entry in entries) {
        val group = entry.group
        if (group != null && group != current) {
            rows.add(MappingListRow.Group(group))
            current = group
        }
        rows.add(MappingListRow.Platform(entry))
    }
    return rows
}
enum class CoreAvailability { AVAILABLE, UNAVAILABLE }
data class EmulatorPickerOption(
    val coreId: String,
    val displayName: String,
    val source: dev.cannoli.scorza.config.EmulatorSource,
    val runnerLabel: String,
    val appPackage: String? = null,
    val availability: CoreAvailability = CoreAvailability.AVAILABLE,
)

enum class MappingActionKind { BIOS, OVERRIDES, RESET }

sealed interface MappingItem {
    val isSelectable: Boolean
    data class SectionHeader(val label: String) : MappingItem { override val isSelectable = false }
    data class Divider(val id: Int = 0) : MappingItem { override val isSelectable = false }
    data class EmulatorOption(val option: EmulatorPickerOption, val isCurrent: Boolean, val downloadable: Boolean = false) : MappingItem {
        override val isSelectable = true
    }
    data class PlatformDefault(val label: String, val isCurrent: Boolean) : MappingItem {
        override val isSelectable = true
    }
    data class Action(
        val kind: MappingActionKind,
        val label: String,
        val status: String = "",
        val statusIsWarning: Boolean = false,
    ) : MappingItem { override val isSelectable = true }
}

data class GameOverrideRow(val romId: Long, val gameName: String, val label: String)

enum class RaTokenState { CHECKING, VALID, INVALID, UNREACHABLE }

data class FirmwareStatus(
    val entry: dev.cannoli.scorza.config.FirmwareEntry,
    val present: Boolean,
    val anyOf: Boolean = false,
    val groupSatisfied: Boolean = false,
)
data class ColorEntry(val key: String, @androidx.annotation.StringRes val labelRes: Int, val hex: String, val color: Long)

sealed interface ListDialog : DialogState {
    val selectedIndex: Int
    fun withSelectedIndex(index: Int): DialogState
}

data class PickerItem(
    val label: String,
    val value: String? = null,
    val dot: Boolean? = null,
    val checked: Boolean? = null,
    val cycles: Boolean = false,
    val clears: Boolean = false,
)

sealed interface DialogState {
    data object None : DialogState

    data class Picker(
        val title: String,
        val items: List<PickerItem>,
        val confirmLabel: String,
        val emptyMessage: String? = null,
        override val selectedIndex: Int = 0,
        val onBack: (() -> Unit)? = null,
        val onCycle: ((index: Int, delta: Int) -> Unit)? = null,
        val onNorth: ((index: Int) -> Unit)? = null,
        val onSelect: (Int) -> Unit,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class MissingCore(
        val coreName: String,
        val platformTag: String? = null,
        val romId: Long? = null,
        val coreId: String = "",
        val platformName: String = "",
    ) : DialogState
    data class UnsupportedContent(
        val platformName: String,
        val coreName: String,
        val extension: String,
        val supported: List<String> = emptyList(),
        val platformTag: String? = null,
        val romId: Long? = null,
    ) : DialogState

    data class MissingApp(
        val appName: String,
        val packageName: String,
        val platformTag: String? = null,
        val romId: Long? = null,
        val platformName: String = "",
    ) : DialogState

    data class MissingBios(
        val platformName: String,
        val files: List<String>,
        val platformTag: String? = null,
        val romId: Long? = null,
    ) : DialogState

    data class NoEmulatorSet(
        val platformName: String,
        val platformTag: String? = null,
        val romId: Long? = null,
    ) : DialogState
    data class LaunchError(val message: String) : DialogState
    data object Launching : DialogState
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class DeleteConfirm(val gameName: String, val bulkPaths: List<String>? = null) : DialogState
    data class RenameInput(
        val target: RenameTarget,
        @androidx.annotation.StringRes override val titleRes: Int? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class NewCollectionInput(
        val gamePaths: List<String> = emptyList(),
        val parentId: Long? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_new_collection
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class CollectionRenameInput(
        val collectionId: Long,
        val oldDisplayName: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_rename_collection
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class DeleteCollectionConfirm(val collectionId: Long, val displayName: String) : DialogState
    data class RenameResult(val success: Boolean, val message: String) : DialogState
    data class CollectionCreated(val collectionName: String) : DialogState
    data class ColorPicker(val settingKey: String, val title: String, val currentColor: Long, val selectedRow: Int = 0, val selectedCol: Int = 0) : DialogState
    data class HexColorInput(val settingKey: String, val title: String, val currentHex: String = "", val selectedIndex: Int = 0) : DialogState
    data class About(val statusMessage: String? = null, val fromQuickMenu: Boolean = false) : DialogState
    data class Kitchen(val urls: List<String>, override val selectedIndex: Int = 0, val pin: String, val requirePin: Boolean = true, val fromQuickMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RALoggingIn(val message: String = "Logging in$ELLIPSIS", val failed: Boolean = false) : DialogState
    data class RAPreloadProgress(val gameName: String) : DialogState
    data class RAPreloadResult(val success: Boolean, val message: String) : DialogState
    data object RetroAchievementsLogoutConfirm : DialogState
    data class ControllerResetConfirm(val mappingId: String) : DialogState
    data class RommPairing(
        val host: String = "",
        val message: String = "",
        val waitingApproval: Boolean = false,
        val qrBitmap: android.graphics.Bitmap? = null,
    ) : DialogState
    data class RommConnected(val host: String, val username: String? = null, val version: String? = null, val fromSettingsMenu: Boolean = false) : DialogState
    data class NewFolderInput(
        val parentPath: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_new_folder
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class KeyboardHelp(val restore: DialogState, val layout: KeyboardLayout) : DialogState
    data class GuideHelp(val guideType: dev.cannoli.igm.GuideType) : DialogState
    data object QuitConfirm : DialogState
    data class UpdateDownload(val versionName: String, val changelog: String, val fromQuickMenu: Boolean = false) : DialogState
    data object RestartRequired : DialogState
    data class LibrarySwitchConfirm(val newRomDirectory: String) : DialogState
    data class IntentAuditResult(val message: String) : DialogState
    data class SystemFoldersRegenerated(val message: String) : DialogState
    data class PlatformResetConfirm(val tag: String, val platformName: String) : DialogState
    data object ResetCustomConfigConfirm : DialogState

    data class InputTesterOffer(val mappingId: String, val deviceId: Int) : DialogState

    data class InputTesterResult(val mappingId: String) : DialogState

    data class UninstallCoreConfirm(val coreId: String, val coreName: String, val bytes: Long) : DialogState

    data class RemoveUnusedCoresConfirm(val cores: Int, val bytes: Long) : DialogState

    data object CheckingCores : DialogState

    data class UpdateCoresConfirm(val cores: Int, val bytes: Long) : DialogState

    data class UpdateShadersConfirm(val bytes: Long, val installedBytes: Long) : DialogState

    data object UpdatingCores : DialogState
    data class PermissionDetail(val permission: dev.cannoli.scorza.permissions.AppPermission) : DialogState
    data class QuickMenu(
        val rows: List<dev.cannoli.scorza.ui.quickmenu.QuickMenuRow>,
        val kitchenRunning: Boolean,
        override val selectedIndex: Int = 0,
        val conflictCount: Int = 0,
        val syncErrorCount: Int = 0,
        val pendingUnlockCount: Int = 0,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class QuickInfo(
        val endpoints: List<dev.cannoli.ui.components.NetworkEndpoint>,
        val kitchenRunning: Boolean,
        val pin: String?,
        val romm: dev.cannoli.ui.components.RommStatus?,
        override val selectedIndex: Int = 0,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommDownloads(override val selectedIndex: Int = 0, val fromQuickMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommArtResults(
        val results: dev.cannoli.scorza.romm.art.ArtFetchResults,
        override val selectedIndex: Int = 0,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RescanProgress(val progress: Float, val label: String) : DialogState
    data class RommConfirm(val action: RommConfirmAction, val downloadKey: String? = null, val fromQuickMenu: Boolean = false) : DialogState
    data class SyncHistory(val entries: List<SyncHistoryRow>, override val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SyncErrors(val errors: List<dev.cannoli.scorza.romm.sync.SyncFailure>, override val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SaveBackupRestoreConfirm(val tag: String, val base: String, val displayName: String, val stamp: Long, val dateLabel: String, val fromContextMenu: Boolean = false) : DialogState
    data class ConflictsMenu(val rows: List<ConflictRow>, override val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data object SaveSyncChecking : DialogState
    data object ConflictsApplying : DialogState
    data class SaveSyncConflict(
        val conflict: dev.cannoli.scorza.romm.sync.PreLaunchOutcome.Conflict,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class SaveSyncStaleBlock(
        val stale: dev.cannoli.scorza.romm.sync.PreLaunchOutcome.KnownStaleBlock,
        val tag: String,
        val base: String,
        val selectedIndex: Int = 0,
    ) : DialogState
}

data class RommVariantEntry(
    val game: dev.cannoli.scorza.romm.RommGame,
    val label: String,
    val present: Boolean,
    val isPrimary: Boolean,
    val hackFile: dev.cannoli.scorza.romm.RommFile? = null,
)

data class RommPlatformToggleItem(val tag: String, val displayName: String, val visible: Boolean)
data class RommCollectionToggleItem(val group: dev.cannoli.scorza.romm.RommCollectionGroup, val displayName: String, val visible: Boolean)

enum class ConflictChoice { KEEP_LOCAL, USE_SERVER, SKIP }
data class ConflictRow(
    val gameKey: String,
    val name: String,
    val choice: ConflictChoice = ConflictChoice.SKIP,
    val localMillis: Long? = null,
    val serverMillis: Long? = null,
)

enum class RommConfirmAction { REBUILD_CACHE, DISCONNECT, CANCEL_DOWNLOAD, CANCEL_ALL }

interface KeyboardHost {
    val keyboard: KeyboardState
    fun withKeyboard(keyboard: KeyboardState): DialogState
    val currentName: String get() = keyboard.text
    val cursorPos: Int get() = keyboard.cursorPos
    @get:androidx.annotation.StringRes val titleRes: Int? get() = null
}

fun DialogState.withMenuDelta(delta: Int): DialogState? = when (this) {
    is DialogState.ContextMenu -> {
        if (options.isEmpty()) null
        else copy(selectedOption = (selectedOption + delta).mod(options.size))
    }
    is DialogState.BulkContextMenu -> {
        if (options.isEmpty()) null
        else copy(selectedOption = (selectedOption + delta).mod(options.size))
    }
    is DialogState.SaveSyncConflict -> copy(selectedIndex = (selectedIndex + delta).mod(2))
    is DialogState.SaveSyncStaleBlock -> copy(selectedIndex = (selectedIndex + delta).mod(2))
    else -> null
}
