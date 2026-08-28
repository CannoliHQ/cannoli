package dev.cannoli.scorza.navigation

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.onboarding.OnboardingPermission
import dev.cannoli.scorza.onboarding.OnboardingPermissionsAction
import dev.cannoli.scorza.ui.components.CREDITS_ROOT_ROWS
import dev.cannoli.scorza.ui.components.CreditsCategory
import dev.cannoli.scorza.ui.components.creditsItemCount
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory

enum class BrowsePurpose { SD_ROOT, ROM_DIRECTORY, SETUP }

sealed class LauncherScreen {
    interface ScrollableScreen {
        val selectedIndex: Int
        val scrollTarget: Int
        val itemCount: Int

        /**
         * Where each selectable row is drawn, when that differs from its position in selection.
         *
         * Selection counts only rows you can land on, while the list renders headers and dividers
         * too, so on such a screen the two index spaces drift apart. A page jump reads the viewport
         * in rendered space and has no way back without this. Null, the default, means every row is
         * selectable and the two are the same.
         */
        val selectableRows: List<Int>? get() = null

        fun withScroll(selectedIndex: Int, scrollTarget: Int): LauncherScreen
    }

    /** Every step of the first-run wizard, so callers can gate on the wizard as a whole. */
    interface OnboardingScreen

    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    /** [quickMenuRow] names the row Back returns to, [quickMenuCategory] the category the quick menu
     *  landed on (null being the category list), so Back only leaves at the level it arrived at. */
    data class Settings(
        val quickMenuRow: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow? = null,
        val quickMenuCategory: SettingsCategory? = null,
    ) : LauncherScreen()
    data object InputTester : LauncherScreen()
    data class SaveStatePicker(
        val rom: dev.cannoli.scorza.model.Rom,
        val stateBasePath: String,
        val slotOccupied: List<Boolean>,
        val selectedSlotIndex: Int,
        val awaitConfirmRelease: Boolean = false,
    ) : LauncherScreen()
    data class SaveSlots(
        val gameKey: String,
        val tag: String,
        val base: String,
        val displayName: String,
        val romId: Int,
        val emulator: String?,
        val slots: List<dev.cannoli.scorza.romm.sync.SlotInfo>,
        val selectedIndex: Int = 0,
        val pendingDelete: Boolean = false,
    ) : LauncherScreen()
    data class GuidePicker(
        val files: List<dev.cannoli.igm.GuideFile>,
        val selectedIndex: Int = 0,
    ) : LauncherScreen()
    data class Guide(
        val filePath: String,
        val guideType: dev.cannoli.igm.GuideType,
        val page: Int = 0,
        val textZoom: Int = 1,
    ) : LauncherScreen()
    data class EmulatorMapping(val mappings: List<EmulatorMappingEntry>, val allMappings: List<EmulatorMappingEntry> = mappings, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val filter: Int = 0, val alphabetical: Boolean = false) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = mappings.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class PlatformMapping(
        val tag: String,
        val platformName: String,
        val items: List<dev.cannoli.scorza.ui.screens.MappingItem>,
        val overridesCount: Int = 0,
        val resettable: Boolean = false,
        // Non-null scopes the screen to a single game: it edits that game's override instead
        // of the platform mapping, and drops the platform-wide actions.
        val romId: Long? = null,
        val gameName: String? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        val selectableItems: List<dev.cannoli.scorza.ui.screens.MappingItem>
            get() = items.filter { it.isSelectable }
        override val itemCount: Int get() = selectableItems.size
        // The same list the renderer builds to place the highlight, so a page jump measures the
        // viewport against the rows actually on screen.
        override val selectableRows: List<Int>
            get() = items.mapIndexedNotNull { idx, it -> idx.takeIf { _ -> it.isSelectable } }
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class BiosStatus(
        val tag: String,
        val platformName: String,
        val coreDisplayName: String,
        val runnerLabel: String,
        val firmware: List<dev.cannoli.scorza.ui.screens.FirmwareStatus>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        // Was hardcoded to 0, so the list never scrolled and cores with long firmware lists
        // (fbneo declares 23 entries) had most of them unreachable.
        override val itemCount: Int get() = firmware.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class InstalledCores(
        val rows: List<dev.cannoli.scorza.launcher.CoreUsage.Row>,
        val totalBytes: Long,
        val reclaimableBytes: Long,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = rows.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) =
            copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class PlatformOverrides(
        val tag: String,
        val platformName: String,
        val overrides: List<dev.cannoli.scorza.ui.screens.GameOverrideRow>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int = overrides.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ColorList(val colors: List<ColorEntry>, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = colors.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = apps.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ChildPicker(val parentId: Long, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Controllers(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ControllerDetail(
        val mappingId: String,
        val androidDeviceId: Int? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 5
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class EditButtons(
        val mappingId: String,
        val listeningCanonical: dev.cannoli.scorza.input.CanonicalButton? = null,
        val countdownMs: Int = 0,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.input.CanonicalButton.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    // Shown full-screen, unskippable, when a pad connects that Cannoli cannot identify. No
    // selectedIndex/scrollTarget: there is nothing to navigate, only raw key capture.
    // The step counter belongs to first run, so the wizard has to know which way it was entered.
    data class LegendWizard(val deviceId: Int, val duringFirstRun: Boolean = false) : LauncherScreen()
    data class LoggingSettings(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.util.LoggingPrefs.Category.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Permissions(
        val states: Map<dev.cannoli.scorza.permissions.AppPermission, dev.cannoli.scorza.permissions.PermissionState>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.permissions.AppPermission.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ShortcutBinding(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(), val listening: Boolean = false, val heldKeys: Set<Int> = emptySet(), val countdownMs: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = ShortcutAction.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Credits(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val fromQuickMenu: Boolean = false) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = CREDITS_ROOT_ROWS.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CreditsSection(
        val category: CreditsCategory,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = creditsItemCount(category)
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommPlatformList(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGroups(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommVirtualTypes(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionList(
        val group: dev.cannoli.scorza.romm.RommCollectionGroup,
        val virtualType: String? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGameList(
        val collection: dev.cannoli.scorza.romm.RommCollection,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGlobalSearch(
        val term: String,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommFirmwareList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val rows: List<dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.RommFirmwareRow> = emptyList(),
        val checkedIds: Set<Int> = emptySet(),
        val loading: Boolean = true,
        val error: Boolean = false,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = rows.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameDetail(
        val game: dev.cannoli.scorza.romm.RommGame,
        val localState: dev.cannoli.scorza.romm.LocalState,
        val platformName: String,
        val tag: String,
        val scrollStep: Int = 0,
        val groupKey: Int = game.groupKey,
        val versionCount: Int = 1,
    ) : LauncherScreen()
    data class RaOfflinePlatform(val tag: String, val name: String, val count: Int)

    data class RetroAchievements(
        val username: String,
        val hardcore: Boolean = false,
        val tokenState: dev.cannoli.scorza.ui.screens.RaTokenState = dev.cannoli.scorza.ui.screens.RaTokenState.CHECKING,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.ui.components.RaAccountRow.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }

    data class RetroAchievementsOfflinePlatforms(
        val platforms: List<RaOfflinePlatform> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = platforms.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RetroAchievementsOfflineSets(
        val platformTag: String = "",
        val platformName: String = "",
        val entries: List<dev.cannoli.scorza.achievements.RaOfflineStore.Entry> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    // Debug builds only. Renders CannoliIcons.all so what you see is what the app draws, rather
    // than a second hand-maintained list that could disagree with it.
    data class IconGallery(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.ui.theme.CannoliIcons.all.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class DirectoryBrowser(
        val purpose: BrowsePurpose,
        val currentPath: String,
        val entries: List<String> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size + if (currentPath != "/storage/") 1 else 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data object OnboardingWelcome : LauncherScreen(), OnboardingScreen

    data class OnboardingPermissions(
        val permissions: List<OnboardingPermission> = emptyList(),
        val granted: Set<OnboardingPermission> = emptySet(),
        val selectedIndex: Int = 0,
    ) : LauncherScreen(), OnboardingScreen {
        // Every row is a permission and continue lives in the footer, so focus is a plain lookup
        // into the list. Adding a permission shifts nothing.
        val focusedPermission: OnboardingPermission? get() = permissions.getOrNull(selectedIndex)
        val isFocusedGranted: Boolean get() = focusedPermission?.let { it in granted } == true
        val canContinue: Boolean get() = permissions.none { it.required && it !in granted }
        val action: OnboardingPermissionsAction
            get() = when {
                focusedPermission == null -> OnboardingPermissionsAction.NONE
                !isFocusedGranted -> OnboardingPermissionsAction.GRANT
                canContinue -> OnboardingPermissionsAction.CONTINUE
                else -> OnboardingPermissionsAction.NONE
            }
        fun moved(delta: Int) = copy(
            selectedIndex = (selectedIndex + delta).coerceIn(0, (permissions.size - 1).coerceAtLeast(0))
        )
    }

    data class OnboardingStorage(
        val volumes: List<Pair<String, String>> = emptyList(),
        val volumeIndex: Int = 0,
        val customPath: String? = null,
        val existingInstallVolumeIndex: Int? = null,
    ) : LauncherScreen(), OnboardingScreen {
        val selectedVolume: Pair<String, String>? get() = volumes.getOrNull(volumeIndex)
        // The custom entry is the one with no path of its own, which is what makes it custom.
        val isCustomVolume: Boolean get() = selectedVolume?.second?.isEmpty() == true
        val canContinue: Boolean
            get() = volumes.isNotEmpty() && (!isCustomVolume || customPath != null)
        val targetPath: String?
            get() {
                if (!canContinue) return null
                return if (isCustomVolume) customPath else selectedVolume!!.second + "Cannoli/"
            }
        val existingFolderPath: String?
            get() = if (existingInstallVolumeIndex == volumeIndex) {
                selectedVolume?.second?.plus("Cannoli/")
            } else {
                null
            }
        fun moved(delta: Int) = copy(
            volumeIndex = (volumeIndex + delta).coerceIn(0, (volumes.size - 1).coerceAtLeast(0))
        )
    }
}
