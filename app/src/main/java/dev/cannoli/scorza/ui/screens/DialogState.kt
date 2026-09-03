package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardState

enum class EmulatorMappingStatus { READY, NOT_INSTALLED, NEEDS_SETUP }
data class EmulatorMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String, val status: EmulatorMappingStatus = EmulatorMappingStatus.READY, val group: String? = null)

/**
 * A row of the emulator mapping list. Headers are separate rows rather than decoration on the
 * first platform of a group, because the list draws every row at one height and selection counts
 * only the selectable ones, which is how the platform picker beside it already works.
 */
sealed interface MappingListRow {
    val isSelectable: Boolean
    data class Group(val label: String) : MappingListRow { override val isSelectable = false }
    data class Platform(val entry: EmulatorMappingEntry) : MappingListRow { override val isSelectable = true }
}

/** Groups entries under manufacturer headers, preserving the order the entries arrive in. */
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
// source is the identity; runnerLabel is display only. Matching on the caption is what made a
// selection vanish whenever the runner's label changed.
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

/**
 * What is known about the stored RetroAchievements token.
 *
 * [UNREACHABLE] is not [INVALID]: failing to reach the server is no evidence the token is bad, and
 * it is not [CHECKING] either, because nothing is still in flight.
 */
enum class RaTokenState { CHECKING, VALID, INVALID, UNREACHABLE }

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

/**
 * One row of a [DialogState.Picker], in the terms the shared list already draws: a label, an
 * optional right-hand value, and the dot the list uses to mark a row as already held.
 *
 * Display only. A picker's builder resolves glyphs, formats sizes and orders the rows, because
 * those are the caller's business and putting them here would make one row type answer to every
 * menu that ever uses it.
 */
data class PickerItem(
    val label: String,
    val value: String? = null,
    val dot: Boolean? = null,
    /** Draws a checkbox. Non-null makes this a toggle row rather than a plain one. */
    val checked: Boolean? = null,
    /** Left and right change this row's value, and the legend says so while it is selected. */
    val cycles: Boolean = false,
    /** North clears this row's value, and the legend says so while it is selected. */
    val clears: Boolean = false,
)

sealed interface DialogState {
    data object None : DialogState

    /**
     * A list of rows and what to do with the chosen one.
     *
     * Nineteen states were the same screen with different contents, and each paid for that with a
     * branch in the renderer, one in the row count, one in back and one in confirm. This carries
     * its own strings and its own action instead, so a menu is a function that builds one of these
     * next to the data it belongs to.
     *
     * [onSelect] holds a lambda, which makes two equal pickers compare unequal, so a rebuilt one
     * always re-emits rather than being conflated by StateFlow. That is the safe direction and it
     * is what the bespoke states it replaces already did.
     */
    data class Picker(
        val title: String,
        val items: List<PickerItem>,
        val confirmLabel: String,
        /** Shown in place of the list when there is nothing in it. Null draws an empty screen. */
        val emptyMessage: String? = null,
        override val selectedIndex: Int = 0,
        /** Where back goes. Null closes, which is what a menu opened from the game list wants. */
        val onBack: (() -> Unit)? = null,
        /** Called with the row and the direction when a [PickerItem.cycles] row is nudged. */
        val onCycle: ((index: Int, delta: Int) -> Unit)? = null,
        /** Called with the row when north is pressed on a [PickerItem.clears] row. */
        val onNorth: ((index: Int) -> Unit)? = null,
        val onSelect: (Int) -> Unit,
    ) : ListDialog {
        override fun withSelectedIndex(index: Int) = copy(selectedIndex = index)
    }
    // romId is set only when the emulator that failed came from a per-game override, so
    // recovery edits the mapping that actually failed instead of the platform-wide one.
    data class MissingCore(
        val coreName: String,
        val platformTag: String? = null,
        val romId: Long? = null,
        // coreName is a display name, which cannot be looked up or downloaded. The id is carried
        // separately so a caller can decide whether the core is one we can fetch.
        val coreId: String = "",
        val platformName: String = "",
    ) : DialogState
    /**
     * The core cannot parse this file. Distinct from a failed load: nothing about the content is
     * wrong, the pairing is. Geolith takes the NeoSD `.neo` format, so a MAME-style Neo Geo romset
     * is not content it fails on, it is content it has no parser for.
     */
    data class UnsupportedContent(
        val platformName: String,
        val coreName: String,
        val extension: String,
        // In the order the core declares them, which leads with the format it is actually for.
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

    /**
     * A BIOS the platform genuinely needs is absent, so the launch is stopped before the emulator
     * gets a chance to hang on it. Required-ness comes from `bios_required.txt` where the core's own
     * metadata cannot express it, which is why this is not simply read off the firmware flags.
     */
    data class MissingBios(
        val platformName: String,
        val files: List<String>,
        val platformTag: String? = null,
        val romId: Long? = null,
    ) : DialogState

    /**
     * The platform resolves to no emulator at all, which is a different problem from one that is
     * merely uninstalled. It used to report a missing core named "unknown", which named a core that
     * was never chosen and told the user nothing.
     */
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
    /** [newRomDirectory] is empty when clearing the pick, which resolves back to the Cannoli root. */
    data class LibrarySwitchConfirm(val newRomDirectory: String) : DialogState
    data class IntentAuditResult(val message: String) : DialogState
    data class SystemFoldersRegenerated(val message: String) : DialogState
    data class PlatformResetConfirm(val tag: String, val platformName: String) : DialogState
    data object ResetCustomConfigConfirm : DialogState

    /** [bytes] is what the one core reclaims; the name is already display text. */
    data class UninstallCoreConfirm(val coreId: String, val coreName: String, val bytes: Long) : DialogState

    data class RemoveUnusedCoresConfirm(val cores: Int, val bytes: Long) : DialogState

    /**
     * Working out what an update would cost, before asking. One index request and a HEAD per
     * differing core, so it is brief but not instant.
     */
    data object CheckingCores : DialogState

    /**
     * About to replace every installed core. The cost is stated before it starts, because cores are
     * rebuilt nightly and this normally means downloading all of them.
     */
    data class UpdateCoresConfirm(val cores: Int, val bytes: Long) : DialogState

    /**
     * [bytes] is what comes down the wire and [installedBytes] what it becomes on the card. The
     * second is the one that matters on a handheld: the archives expand about twelvefold.
     */
    data class UpdateShadersConfirm(val bytes: Long, val installedBytes: Long) : DialogState

    /** A run in progress. The figures come from the service, not from here. */
    data object UpdatingCores : DialogState
    data class PermissionDetail(val permission: dev.cannoli.scorza.permissions.AppPermission) : DialogState
    data class QuickMenu(
        val rows: List<dev.cannoli.scorza.ui.quickmenu.QuickMenuRow>,
        val kitchenRunning: Boolean,
        override val selectedIndex: Int = 0,
        val conflictCount: Int = 0,
        val syncErrorCount: Int = 0,
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
