package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardState

enum class EmulatorMappingStatus { READY, NOT_INSTALLED, NEEDS_SETUP, UNKNOWN }
data class EmulatorMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String, val status: EmulatorMappingStatus = EmulatorMappingStatus.READY)
// Three-valued because a boolean forced "not reported" and "confirmed absent" to share a
// value, which is how the picker came to label unknowable cores Not Installed.
enum class CoreAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
// source is the identity; runnerLabel is display only. Matching on the caption is what made a
// selection vanish whenever the configured RetroArch package changed its label.
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
    data class Notice(val text: String) : MappingItem { override val isSelectable = false }
    data class Divider(val id: Int = 0) : MappingItem { override val isSelectable = false }
    data class EmulatorOption(val option: EmulatorPickerOption, val isCurrent: Boolean, val downloadable: Boolean = false) : MappingItem {
        override val isSelectable = true
    }
    /** Game scope only: selecting this clears the per-game override and follows the platform. */
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

/** One row of the per-game overrides list. Keyed by rom_id, not by path. */
data class GameOverrideRow(val romId: Long, val gameName: String, val label: String)

data class FirmwareStatus(val entry: dev.cannoli.scorza.config.FirmwareEntry, val present: Boolean)
data class ColorEntry(val key: String, @androidx.annotation.StringRes val labelRes: Int, val hex: String, val color: Long)

/**
 * A dialog whose rows are navigated with up and down.
 *
 * Every such dialog wrapped the index modulo its own count, written once per dialog because only
 * the count differed. The count still varies, and for some of them it is not the state's to know,
 * so it stays with the handler; moving between rows does not.
 */
sealed interface ListDialog : DialogState {
    val selectedIndex: Int
    fun withSelectedIndex(index: Int): DialogState
}

sealed interface DialogState {
    data object None : DialogState
    // romId is set only when the emulator that failed came from a per-game override, so
    // recovery edits the mapping that actually failed instead of the platform-wide one.
    data class MissingCore(
        val coreName: String,
        val packageLabel: String? = null,
        val platformTag: String? = null,
        val romId: Long? = null,
    ) : DialogState
    data class MissingApp(val appName: String, val packageName: String, val platformTag: String? = null, val romId: Long? = null) : DialogState
    data class LaunchError(val message: String) : DialogState
    data object Launching : DialogState
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class DeleteConfirm(val gameName: String, val bulkPaths: List<String>? = null) : DialogState
    data class RenameInput(
        val gameName: String,
        val searchScope: String? = null,
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
    data class About(val statusMessage: String? = null) : DialogState
    data class Kitchen(val urls: List<String>, val selectedIndex: Int = 0, val pin: String, val requirePin: Boolean = true, val fromQuickMenu: Boolean = false) : DialogState
    data class RAAccount(val username: String, val score: Int = 0) : DialogState
    data class RALoggingIn(val message: String = "Logging in$ELLIPSIS") : DialogState
    data class RAPreloadProgress(val gameName: String) : DialogState
    data class RAPreloadResult(val success: Boolean, val message: String) : DialogState
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
    data object QuitConfirm : DialogState
    data class UpdateDownload(val versionName: String, val changelog: String) : DialogState
    data object RestartRequired : DialogState
    /** [newRomDirectory] is empty when clearing the pick, which resolves back to the Cannoli root. */
    data class LibrarySwitchConfirm(val newRomDirectory: String) : DialogState
    data class IntentAuditResult(val message: String) : DialogState
    data class SystemFoldersRegenerated(val message: String) : DialogState
    data class PlatformResetConfirm(val tag: String, val platformName: String) : DialogState
    data class QuickMenu(
        val rows: List<dev.cannoli.scorza.ui.quickmenu.QuickMenuRow>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
        val conflictCount: Int = 0,
        val syncErrorCount: Int = 0,
    ) : DialogState
    data class QuickInfo(
        val urls: List<String>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RommDownloads(override val selectedIndex: Int = 0, val fromQuickMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommArtResults(
        val results: dev.cannoli.scorza.romm.art.ArtFetchResults,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RescanProgress(val progress: Float, val label: String) : DialogState
    data class RommActionsMenu(
        val selectedIndex: Int = 0,
        val hasDownloads: Boolean = false,
    ) : DialogState
    data class RommSettingsMenu(
        val selectedIndex: Int = 0,
        val concurrent: Int = 2,
        val artType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    ) : DialogState
    data class RommAdvancedMenu(override val selectedIndex: Int = 0) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommSaveSyncMenu(
        val selectedIndex: Int = 0,
        val supported: Boolean = true,
        val enabled: Boolean = false,
        val backupCount: Int = 5,
        val pendingConflicts: Int = 0,
        val syncErrors: Int = 0,
        val hasBackups: Boolean = false,
    ) : DialogState
    data class RommConfirm(val action: RommConfirmAction, val downloadKey: String? = null, val fromQuickMenu: Boolean = false) : DialogState
    data class RommPlatformToggle(val items: List<RommPlatformToggleItem>, override val selectedIndex: Int = 0) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommCollectionToggle(val items: List<RommCollectionToggleItem>, override val selectedIndex: Int = 0) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SyncHistory(val entries: List<SyncHistoryRow>, override val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SyncErrors(val errors: List<dev.cannoli.scorza.romm.sync.SyncFailure>, override val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class RommSavesMenu(val title: String, val options: List<String>, override val selectedIndex: Int = 0) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SaveBackupGames(val games: List<dev.cannoli.scorza.romm.sync.SaveBackupGame>, override val selectedIndex: Int = 0) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    data class SaveBackupList(val tag: String, val base: String, val displayName: String, val backups: List<dev.cannoli.scorza.romm.sync.SaveBackup>, override val selectedIndex: Int = 0, val fromContextMenu: Boolean = false) : ListDialog {
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
    data class RommVersionPicker(
        val gameName: String,
        val tag: String,
        val members: List<RommVariantEntry>,
        override val selectedIndex: Int = 0,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
}

data class RommVariantEntry(val game: dev.cannoli.scorza.romm.RommGame, val label: String, val present: Boolean, val isPrimary: Boolean)

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
