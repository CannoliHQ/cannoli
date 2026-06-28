package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.ELLIPSIS
import dev.cannoli.ui.components.KeyboardState

enum class EmulatorMappingStatus { READY, NOT_INSTALLED, NEEDS_SETUP }
data class EmulatorMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String, val status: EmulatorMappingStatus = EmulatorMappingStatus.READY)
data class EmulatorPickerOption(val coreId: String, val displayName: String, val runnerLabel: String, val appPackage: String? = null, val available: Boolean = true)

enum class MappingActionKind { BIOS, OVERRIDES, RESET }

sealed interface MappingItem {
    val isSelectable: Boolean
    data class SectionHeader(val label: String) : MappingItem { override val isSelectable = false }
    data class Divider(val id: Int = 0) : MappingItem { override val isSelectable = false }
    data class EmulatorOption(val option: EmulatorPickerOption, val isCurrent: Boolean, val downloadable: Boolean = false) : MappingItem {
        override val isSelectable = true
    }
    data class Action(
        val kind: MappingActionKind,
        val label: String,
        val status: String = "",
        val statusIsWarning: Boolean = false,
    ) : MappingItem { override val isSelectable = true }
}

data class FirmwareStatus(val entry: dev.cannoli.scorza.config.FirmwareEntry, val present: Boolean)
data class ColorEntry(val key: String, @androidx.annotation.StringRes val labelRes: Int, val hex: String, val color: Long)

sealed interface DialogState {
    data object None : DialogState
    data class MissingCore(
        val coreName: String,
        val packageLabel: String? = null,
    ) : DialogState
    data class MissingApp(val appName: String, val packageName: String) : DialogState
    data class LaunchError(val message: String) : DialogState
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class DeleteConfirm(val gameName: String, val bulkPaths: List<String>? = null) : DialogState
    data class RenameInput(
        val gameName: String,
        val searchScope: String? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class NewCollectionInput(
        val gamePaths: List<String> = emptyList(),
        val parentId: Long? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class CollectionRenameInput(
        val collectionId: Long,
        val oldDisplayName: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
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
    data class RommPairing(val host: String = "", val message: String = "") : DialogState
    data class RommConnected(val host: String, val username: String? = null, val version: String? = null) : DialogState
    data class NewFolderInput(
        val parentPath: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data object QuitConfirm : DialogState
    data class UpdateDownload(val versionName: String, val changelog: String) : DialogState
    data object RestartRequired : DialogState
    data class IntentAuditResult(val message: String) : DialogState
    data class SystemFoldersRegenerated(val message: String) : DialogState
    data class PlatformResetConfirm(val tag: String, val platformName: String) : DialogState
    data class QuickMenu(
        val rows: List<dev.cannoli.scorza.ui.quickmenu.QuickMenuRow>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class QuickInfo(
        val urls: List<String>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RommDownloads(val selectedIndex: Int = 0) : DialogState
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
    data class RommAdvancedMenu(val selectedIndex: Int = 0) : DialogState
    data class RommConfirm(val action: RommConfirmAction, val downloadKey: String? = null) : DialogState
    data class RommPlatformToggle(val items: List<RommPlatformToggleItem>, val selectedIndex: Int = 0) : DialogState
    data class RommCollectionToggle(val items: List<RommCollectionToggleItem>, val selectedIndex: Int = 0) : DialogState
}

data class RommPlatformToggleItem(val tag: String, val displayName: String, val visible: Boolean)
data class RommCollectionToggleItem(val group: dev.cannoli.scorza.romm.RommCollectionGroup, val displayName: String, val visible: Boolean)

enum class RommConfirmAction { REBUILD_CACHE, DISCONNECT, CANCEL_DOWNLOAD, CANCEL_ALL }

interface KeyboardHost {
    val keyboard: KeyboardState
    fun withKeyboard(keyboard: KeyboardState): DialogState
    val currentName: String get() = keyboard.text
    val cursorPos: Int get() = keyboard.cursorPos
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
    else -> null
}

val DialogState.isFullScreen: Boolean
    get() = when (this) {
        is DialogState.ContextMenu,
        is DialogState.BulkContextMenu,
        is DialogState.ColorPicker,
        is DialogState.HexColorInput,
        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput,
        is DialogState.NewFolderInput,
        is DialogState.About,
        is DialogState.Kitchen,
        is DialogState.RAAccount,
        is DialogState.RALoggingIn,
        is DialogState.RAPreloadProgress,
        is DialogState.RAPreloadResult,
        is DialogState.RommPairing,
        is DialogState.RommConnected,
        is DialogState.UpdateDownload,
        is DialogState.RestartRequired,
        is DialogState.IntentAuditResult,
        is DialogState.SystemFoldersRegenerated,
        is DialogState.QuickMenu,
        is DialogState.RommDownloads,
        is DialogState.RommArtResults,
        is DialogState.RescanProgress,
        is DialogState.RommActionsMenu,
        is DialogState.RommSettingsMenu,
        is DialogState.RommAdvancedMenu,
        is DialogState.RommConfirm,
        is DialogState.RommPlatformToggle,
        is DialogState.RommCollectionToggle,
        is DialogState.QuickInfo -> true
        else -> false
    }
