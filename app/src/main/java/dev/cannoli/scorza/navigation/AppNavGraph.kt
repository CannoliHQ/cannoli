package dev.cannoli.scorza.navigation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.scorza.onboarding.OnboardingPermission
import dev.cannoli.scorza.onboarding.OnboardingPermissionsAction
import dev.cannoli.scorza.ui.LocalViewportInsets
import dev.cannoli.scorza.util.buttonLabel
import dev.cannoli.scorza.ui.ViewportInsetsPx
import dev.cannoli.scorza.ui.components.CREDITS_ROOT_ROWS
import dev.cannoli.scorza.ui.components.CreditsCategory
import dev.cannoli.scorza.ui.components.CreditsCategoryOverlay
import dev.cannoli.scorza.ui.components.CreditsOverlay
import dev.cannoli.scorza.ui.components.creditsItemCount
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.effectiveViewportPadding
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.ControllerDetailScreen
import dev.cannoli.scorza.ui.screens.ControllersScreen
import dev.cannoli.scorza.ui.screens.EditButtonsScreen
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import dev.cannoli.scorza.ui.screens.EmulatorPickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.GuidePickerScreen
import dev.cannoli.scorza.ui.screens.GuideViewerScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.LegendWizardScreen
import dev.cannoli.scorza.ui.screens.LoggingSettingsScreen
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.PermissionsScreen
import dev.cannoli.scorza.ui.screens.PortraitMarginOverlay
import dev.cannoli.scorza.ui.screens.SaveSlotsScreen
import dev.cannoli.scorza.ui.screens.SaveStatePickerScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.RommCacheSyncStatus
import dev.cannoli.ui.components.SectionHeader
import dev.cannoli.ui.components.SectionNotice
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.LocalListRhythm
import dev.cannoli.ui.components.LocalUntitledListRhythm
import dev.cannoli.ui.components.bottomBarHeight
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillLineHeightSp
import dev.cannoli.ui.components.pillNominalGap
import dev.cannoli.ui.components.pillScaleFor
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.components.screenTitleMetrics
import dev.cannoli.ui.components.solveListRhythm
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

enum class BrowsePurpose { SD_ROOT, ROM_DIRECTORY, SETUP }

// RomM brand purple; the screen-edge border shown while browsing RomM.
private val ROMM_BORDER_COLOR = Color(0xFF553E98)
private val ROMM_BORDER_WIDTH = 2.dp

@Composable
private fun RommBorderFrame() {
    Box(modifier = Modifier.fillMaxSize().border(ROMM_BORDER_WIDTH, ROMM_BORDER_COLOR))
}

private const val CACHE_SYNC_INDICATOR_DELAY_MS = 1200L
private const val CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS = 800L

/**
 * Status-bar state for the RomM cache mirror, shown only while browsing RomM. A stale mirror
 * outranks an in-flight sync so a failing retry cannot blink the alert off and back on, and the
 * syncing glyph is withheld until the sync has run long enough to be worth mentioning. Once shown
 * it stays up for a minimum span, so a sync that finishes just past the threshold does not flash.
 */
@Composable
private fun rommCacheSyncIndicator(
    status: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus,
    stale: Boolean,
    inRomm: Boolean,
): RommCacheSyncStatus {
    val syncing = inRomm && status == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING
    var showSyncing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(syncing) {
        if (syncing) {
            delay(CACHE_SYNC_INDICATOR_DELAY_MS)
            shownAt = SystemClock.elapsedRealtime()
            showSyncing = true
        } else if (showSyncing) {
            val held = SystemClock.elapsedRealtime() - shownAt
            if (held < CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS) delay(CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS - held)
            showSyncing = false
        }
    }
    return when {
        !inRomm -> RommCacheSyncStatus.IDLE
        stale -> RommCacheSyncStatus.ERROR
        showSyncing -> RommCacheSyncStatus.SYNCING
        else -> RommCacheSyncStatus.IDLE
    }
}

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
        val quickMenuCategory: String? = null,
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

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel? = null,
    gameListViewModel: GameListViewModel? = null,
    inputTesterViewModel: InputTesterViewModel,
    onExitInputTester: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    controllersViewModel: ControllersViewModel,
    dialogState: StateFlow<DialogState>,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    resumableGames: Set<String> = emptySet(),
    updateAvailable: Boolean = false,
    downloadProgress: Float = 0f,
    coreUpdate: dev.cannoli.scorza.launcher.CoreDownloadService.UpdateProgress? = null,
    downloadError: String? = null,
    osdController: dev.cannoli.ui.components.OsdController,
    activeMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController? = null,
    legendWizardState: dev.cannoli.scorza.input.legend.LegendWizardState = dev.cannoli.scorza.input.legend.LegendWizardState(),
    onboardingMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    onboardingConfirmPresses: Int = 0,
    onOnboardingRunExpired: () -> Unit = {},
    nav: dev.cannoli.scorza.navigation.NavigationController? = null,
    inputRouter: dev.cannoli.scorza.input.InputRouter? = null,
    rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel? = null,
    rommImageLoader: coil.ImageLoader? = null,
    rommHost: String = "",
    rommArtType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    rommDownloader: dev.cannoli.scorza.download.Downloader? = null,
    rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher? = null,
    saveSyncStatus: dev.cannoli.ui.components.SaveSyncStatus = dev.cannoli.ui.components.SaveSyncStatus.DISABLED,
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val pillScale = pillScaleFor(appSettings.textSize.sp)
    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = pillLineHeightSp(appSettings.textSize.sp).sp

    val labels = dev.cannoli.ui.ButtonStyle(
        activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        activeMapping.confirmButton(),
    )

    val labelContext = androidx.compose.ui.platform.LocalContext.current
    val shortcutKeyLabel: (Int) -> String = { keyCode ->
        buttonLabel(
            labelContext,
            keyCode,
            activeMapping,
            activeMapping?.glyphStyle ?: dev.cannoli.scorza.input.GlyphStyle.PLUMBER,
        )
    }

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent,
        title = appSettings.colorTitle,
        background = appSettings.colorBackground,
        statusBar = appSettings.colorStatusBar
    )

    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val scaleFactor = appSettings.textSize.sp / 22f
    val cannoliTypography = buildCannoliTypography(baseSizeSp = appSettings.textSize.sp, fontFamily = LocalCannoliFont.current)

    val viewportInsets = ViewportInsetsPx(
        geometryWidthPct = appSettings.screenGeometryWidth,
        geometryHeightPct = appSettings.screenGeometryHeight,
        geometryXPct = appSettings.screenGeometryX,
        geometryYPct = appSettings.screenGeometryY,
        portraitMarginPx = appSettings.portraitMarginPx,
    )
    CompositionLocalProvider(
        LocalCannoliColors provides cannoliColors,
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides scaleFactor,
        LocalCannoliTypography provides cannoliTypography,
        LocalViewportInsets provides viewportInsets,
        LocalPillScale provides pillScale
    ) {
    // Must resolve inside the provider so they match what PillRow actually renders.
    val listVerticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val settingsState = settingsViewModel.state.collectAsState().value
    // The margin preview marks the band the game will NOT draw into, so it has to measure the whole
    // screen. Drawn inside the padded box below it would measure an area the margin has already been
    // subtracted from, and at a large margin the band collapses to cover everything.
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == "display"
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == "portrait_margin"
    Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().displayCutoutPadding().padding(effectiveViewportPadding())) {
    // Solved once here so the title spacer, the row spacing and the footer reservation all come from
    // the same division of the screen; every list screen lays out inside these same bounds.
    val insets = screenInsets()
    // Every fixed height below is laid out as whole pixels, so the solve has to round the same way
    // or its row count drifts from the one Compose actually places.
    val density = LocalDensity.current
    fun Dp.snap(): Dp = with(density) { roundToPx().toDp() }
    val available = (maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()).snap()
    val titleMetrics = screenTitleMetrics(listFontSize, listLineHeight)
    val barHeight = bottomBarHeight().snap()
    val rowHeight = itemHeight.snap()
    val pixel = with(density) { 1.toDp() }
    val titleHeight = titleMetrics.height.snap()
    // What each end needs beyond the shared row spacing for the gaps to read alike: under the title,
    // the row's own below-ink space less the title's deeper descent; over the footer, the row's
    // above-ink leading, which the legend pill's solid edge has no counterpart for.
    val edge = pillNominalGap() / 2
    val topExtra = titleMetrics.rowBelowInk + edge - titleMetrics.titleBelowInk
    val bottomExtra = titleMetrics.rowAboveInk + edge
    val rhythm =
        solveListRhythm(
            available = available, titleHeight = titleHeight, barHeight = barHeight,
            rowHeight = rowHeight, topExtra = topExtra, bottomExtra = bottomExtra,
            titled = true, pixel = pixel,
        )
    val untitledRhythm =
        solveListRhythm(
            available = available, titleHeight = titleHeight, barHeight = barHeight,
            rowHeight = rowHeight, topExtra = topExtra, bottomExtra = bottomExtra,
            titled = false, pixel = pixel,
        )
    CompositionLocalProvider(
        LocalListRhythm provides rhythm,
        LocalUntitledListRhythm provides untitledRhythm
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            is LauncherScreen.SystemList -> {
                if (systemListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.systemListHandler) }
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    title = appSettings.title,
                    mainMenuQuit = appSettings.mainMenuQuit,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    fiveGameHandheld = appSettings.contentMode == dev.cannoli.scorza.settings.ContentMode.FIVE_GAME_HANDHELD,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.GameList -> {
                if (gameListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.gameListHandler) }
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.InputTester -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.inputTesterHandler) }
                InputTesterScreen(
                    viewModel = inputTesterViewModel,
                    buttonStyle = labels,
                    onExit = onExitInputTester,
                )
            }
            is LauncherScreen.Settings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.settingsHandler) }
                SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.EmulatorMapping -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val filterLabel = when (currentScreen.filter) {
                    1 -> stringResource(R.string.filter_missing)
                    2 -> stringResource(R.string.filter_unmapped)
                    3 -> stringResource(R.string.filter_mapped)
                    else -> stringResource(R.string.filter_all)
                }
                val selected = currentScreen.mappings.getOrNull(currentScreen.selectedIndex)
                val canSelect = selected != null
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_emulator_mapping),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    leftBottomItems = listOf(
                        labels.west to if (currentScreen.alphabetical)
                            stringResource(R.string.label_group_alphabetical)
                        else
                            stringResource(R.string.label_group_manufacturer),
                        labels.north to filterLabel,
                    ),
                    rightBottomItems = buildList {
                        if (canSelect) add(labels.confirm to stringResource(R.string.label_select))
                    },
                    buttonStyle = labels
                ) {
                    // Headers are rows, so the highlight is translated from the selectable-only
                    // index the screen state carries, the same way PlatformMapping does it. Nothing
                    // in the input path has to know grouping exists.
                    val mappingRows = remember(currentScreen.mappings, currentScreen.alphabetical) {
                        if (currentScreen.alphabetical)
                            currentScreen.mappings.map { dev.cannoli.scorza.ui.screens.MappingListRow.Platform(it) }
                        else
                            dev.cannoli.scorza.ui.screens.groupMappingRows(currentScreen.mappings)
                    }
                    val mappingHighlight = remember(mappingRows, currentScreen.selectedIndex) {
                        mappingRows.withIndex()
                            .filter { it.value.isSelectable }
                            .getOrNull(currentScreen.selectedIndex)?.index ?: -1
                    }
                    List(
                        items = mappingRows,
                        selectedIndex = mappingHighlight,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, row, isSelected ->
                        if (row is dev.cannoli.scorza.ui.screens.MappingListRow.Group) {
                            SectionHeader(
                                text = row.label,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            return@List
                        }
                        val entry = (row as dev.cannoli.scorza.ui.screens.MappingListRow.Platform).entry
                        val value = when {
                            entry.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NEEDS_SETUP -> stringResource(R.string.value_unmapped)
                            entry.runnerLabel.isEmpty() -> entry.coreDisplayName
                            else -> "${entry.coreDisplayName} (${entry.runnerLabel})"
                        }
                        val valueIcon = when (entry.status) {
                            dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED -> CannoliIcons.NotInstalled.glyph
                            else -> null
                        }
                        PillRowKeyValue(
                            label = entry.platformName,
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            valueIcon = valueIcon
                        )
                    }
                }
            }
            is LauncherScreen.PlatformMapping -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val selectableIndices = remember(currentScreen.items) {
                    currentScreen.items.mapIndexedNotNull { idx, it -> if (it.isSelectable) idx else null }
                }
                val highlightedIndex = selectableIndices.getOrNull(
                    currentScreen.selectedIndex.coerceIn(0, (selectableIndices.size - 1).coerceAtLeast(0))
                ) ?: -1
                val highlighted = currentScreen.items.getOrNull(highlightedIndex)
                val confirmLabel = if ((highlighted as? dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption)?.downloadable == true)
                    stringResource(R.string.label_download)
                else
                    stringResource(R.string.label_select)
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = if (currentScreen.romId != null)
                        stringResource(R.string.title_game_mapping, currentScreen.gameName.orEmpty())
                    else
                        stringResource(R.string.title_platform_mapping, currentScreen.platformName),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        if (highlightedIndex >= 0) add(labels.confirm to confirmLabel)
                    },
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.items,
                        selectedIndex = highlightedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, item, isSelected ->
                        when (item) {
                            is dev.cannoli.scorza.ui.screens.MappingItem.SectionHeader -> SectionHeader(
                                text = item.label,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            is dev.cannoli.scorza.ui.screens.MappingItem.PlatformDefault -> PillRowKeyValue(
                                label = item.label,
                                value = if (item.isCurrent) stringResource(R.string.value_active) else "",
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            is dev.cannoli.scorza.ui.screens.MappingItem.Divider -> Spacer(
                                modifier = Modifier.height(listVerticalPadding * 2)
                            )
                            is dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption -> {
                                val opt = item.option
                                val value = when {
                                    item.isCurrent -> stringResource(R.string.value_active)
                                    opt.availability == dev.cannoli.scorza.ui.screens.CoreAvailability.UNAVAILABLE -> {
                                        val resId = when (opt.runnerLabel) {
                                            "Internal" -> R.string.value_not_downloaded
                                            else -> R.string.value_not_installed
                                        }
                                        stringResource(resId)
                                    }
                                    else -> ""
                                }
                                PillRowKeyValue(
                                    label = opt.displayName,
                                    value = value,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding,
                                    valueIcon = if (item.isCurrent &&
                                        opt.availability == dev.cannoli.scorza.ui.screens.CoreAvailability.UNAVAILABLE
                                    ) CannoliIcons.NotInstalled.glyph else null
                                )
                            }
                            is dev.cannoli.scorza.ui.screens.MappingItem.Action -> {
                                val value = item.status
                                if (item.statusIsWarning) {
                                    PillRowKeyValue(
                                        label = item.label,
                                        value = value,
                                        isSelected = isSelected,
                                        fontSize = listFontSize,
                                        lineHeight = listLineHeight,
                                        verticalPadding = listVerticalPadding,
                                        valueIcon = CannoliIcons.NotInstalled.glyph
                                    )
                                } else {
                                    PillRowKeyValue(
                                        label = item.label,
                                        value = value,
                                        isSelected = isSelected,
                                        fontSize = listFontSize,
                                        lineHeight = listLineHeight,
                                        verticalPadding = listVerticalPadding
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.BiosStatus -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_platform_bios, currentScreen.platformName),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "${currentScreen.coreDisplayName} · ${currentScreen.runnerLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cannoliColors.accent,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                        if (currentScreen.firmware.isEmpty()) {
                            Text(
                                text = stringResource(R.string.value_no_firmware),
                                style = MaterialTheme.typography.bodyLarge,
                                color = cannoliColors.text.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 14.dp, top = 6.dp)
                            )
                        } else {
                            List(
                                items = currentScreen.firmware,
                                selectedIndex = -1,
                                itemHeight = itemHeight,
                                scrollTarget = currentScreen.scrollTarget,
                                onListStateChanged = onListStateChanged,
                                modifier = Modifier.weight(1f)
                            ) { _, fw, _ ->
                                val required = !fw.entry.optional
                                val tag = stringResource(if (required) R.string.bios_required else R.string.bios_optional)
                                val statusText = stringResource(if (fw.present) R.string.bios_present else R.string.bios_missing)
                                val requiredMissing = required && !fw.present
                                val rowColor = if (!fw.present && !required) cannoliColors.text.copy(alpha = 0.5f) else cannoliColors.text
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = listVerticalPadding)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = fw.entry.path,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontSize = listFontSize
                                            ),
                                            color = rowColor,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = cannoliColors.accent.copy(alpha = 0.8f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (requiredMissing) {
                                            Text(
                                                text = CannoliIcons.NotInstalled.glyph,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = dev.cannoli.ui.theme.LocalCannoliIconFont.current,
                                                    fontSize = listFontSize,
                                                ),
                                                color = cannoliColors.text
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = rowColor
                                        )
                                    }
                                    Text(
                                        text = fw.entry.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cannoliColors.text.copy(alpha = 0.55f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.PlatformOverrides -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platformName,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        if (currentScreen.overrides.isNotEmpty()) add(labels.north to stringResource(R.string.label_clear_override))
                    },
                    buttonStyle = labels
                ) {
                    if (currentScreen.overrides.isEmpty()) {
                        Text(
                            text = stringResource(R.string.value_no_overrides),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.overrides,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { _, item, isSelected ->
                            PillRowKeyValue(
                                label = item.gameName,
                                value = item.label,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.InstalledCores -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val highlighted = currentScreen.rows.getOrNull(currentScreen.selectedIndex)
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(
                        R.string.title_installed_cores,
                        android.text.format.Formatter.formatShortFileSize(ctx, currentScreen.totalBytes),
                    ),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        // Only offered on a row it can act on: a core something still names has no
                        // uninstall, so the legend does not advertise an action that would refuse.
                        if (highlighted != null && !highlighted.inUse) {
                            add(labels.confirm to stringResource(R.string.label_uninstall))
                        }
                        if (currentScreen.reclaimableBytes > 0L) {
                            add(labels.north to stringResource(R.string.label_remove_unused))
                        }
                    },
                    buttonStyle = labels
                ) {
                    if (currentScreen.rows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.value_no_installed_cores),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.rows,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged,
                            modifier = Modifier.fillMaxSize()
                        ) { _, row, isSelected ->
                            val size = android.text.format.Formatter.formatShortFileSize(ctx, row.sizeBytes)
                            PillRowKeyValue(
                                label = row.displayName,
                                value = stringResource(
                                    if (row.inUse) R.string.value_core_used else R.string.value_core_unused,
                                    size,
                                ),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.ColorList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_colors),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.colors,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        PillRowKeyValue(
                            label = stringResource(entry.labelRes),
                            value = entry.hex.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = Color(entry.color.toInt())
                        )
                    }
                }
            }
            is LauncherScreen.CollectionPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    leftBottomItems = listOf(
                        labels.west to stringResource(R.string.label_new)
                    ),
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
                val d = dialog
                if (d is DialogState.CollectionCreated) {
                    MessageOverlay(message = stringResource(R.string.collection_created, d.collectionName), buttonStyle = labels)
                }
            }
            is LauncherScreen.ChildPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_child_collections),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
            }
            is LauncherScreen.AppPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.apps,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { index, app, isSelected ->
                        PillRowText(
                            label = app,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in currentScreen.checkedIndices
                        )
                    }
                }
            }
            is LauncherScreen.ShortcutBinding -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_shortcuts),

                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = if (currentScreen.listening) listOf("" to stringResource(R.string.label_hold_buttons))
                        else listOf(labels.north to stringResource(R.string.label_clear), labels.confirm to stringResource(R.string.label_set)),
                    buttonStyle = labels
                ) {
                    List(
                        items = ShortcutAction.entries.toList(),
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, action, isSelected ->
                        val chord = currentScreen.shortcuts[action]
                        val value = if (chord.isNullOrEmpty()) stringResource(R.string.value_none)
                        else chord.joinToString(" + ") { shortcutKeyLabel(it) }
                        PillRowKeyValue(
                            label = stringResource(action.labelRes),
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
                if (currentScreen.listening) {
                    val colors = LocalCannoliColors.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        ) {
                            val actionName = ShortcutAction.entries.getOrNull(currentScreen.selectedIndex)
                                ?.let { stringResource(it.labelRes) } ?: ""
                            Text(
                                text = actionName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 24.sp,
                                    color = colors.text
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Sm))
                            Text(
                                text = if (currentScreen.heldKeys.isEmpty()) stringResource(R.string.shortcut_hold_prompt)
                                else currentScreen.heldKeys.joinToString(" + ") { shortcutKeyLabel(it) },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 16.sp,
                                    color = colors.text.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Lg))
                            if (currentScreen.heldKeys.isNotEmpty()) {
                                val progress = (currentScreen.countdownMs / 1500f).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp).fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(Radius.Sm))
                                        .background(colors.text.copy(alpha = 0.2f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progress)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(Radius.Sm))
                                            .background(colors.highlight)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.IconGallery -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_icon_gallery),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    val sections = remember {
                        CannoliIcons.all.groupBy { it.category }
                            .map { (header, icons) -> ListSection(header = header, items = icons) }
                    }
                    SectionedList(
                        sections = sections,
                        selectedIndex = currentScreen.selectedIndex,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, icon, isSelected ->
                        // The glyph sits immediately left of the name it claims to be, so a
                        // codepoint that resolves to the wrong glyph is visible at a glance.
                        PillRowKeyValue(
                            label = "${icon.constantName}: ${icon.purpose}",
                            value = icon.glyphName,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            valueIcon = icon.glyph,
                        )
                    }
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.directoryBrowserHandler) }
                DirectoryBrowserScreen(
                    currentPath = currentScreen.currentPath,
                    entries = currentScreen.entries,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    isSelectRow = currentScreen.selectedIndex == 0,
                    showSelectOption = currentScreen.currentPath != "/storage/",
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels
                )
            }
            is LauncherScreen.Credits -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                CreditsOverlay(
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.CreditsSection -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                CreditsCategoryOverlay(
                    category = currentScreen.category,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.Controllers -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllersHandler) }
                ControllersScreen(
                screen = currentScreen,
                viewModel = controllersViewModel,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.ControllerDetail -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllerDetailHandler) }
                val controllersState by controllersViewModel.state.collectAsState()
                val mapping = controllersState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: controllersState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                ControllerDetailScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.EditButtons -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.editButtonsHandler) }
                val editState by controllersViewModel.state.collectAsState()
                val mapping = editState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: editState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                    ?: controllersViewModel.mappingById(currentScreen.mappingId)
                if (editButtonsController != null && nav != null) {
                    androidx.compose.runtime.LaunchedEffect(currentScreen.listeningCanonical) {
                        if (currentScreen.listeningCanonical != null) {
                            val startedAt = System.currentTimeMillis()
                            while (currentScreen.listeningCanonical != null) {
                                kotlinx.coroutines.delay(50)
                                val finalized = editButtonsController.tickAndMaybeFinalize()
                                if (finalized != null || !editButtonsController.isListening) {
                                    val cs = nav.currentScreen
                                    if (cs is LauncherScreen.EditButtons) {
                                        nav.replaceTop(cs.copy(listeningCanonical = null, countdownMs = 0))
                                    }
                                    break
                                }
                                val cs = nav.currentScreen
                                if (cs is LauncherScreen.EditButtons && cs.listeningCanonical != null) {
                                    val elapsed = (System.currentTimeMillis() - startedAt).toInt()
                                    if (cs.countdownMs != elapsed) {
                                        nav.replaceTop(cs.copy(countdownMs = elapsed))
                                    }
                                }
                            }
                        }
                    }
                }
                EditButtonsScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.LegendWizard -> {
                LegendWizardScreen(
                    state = legendWizardState,
                    modifier = Modifier.fillMaxSize(),
                    duringFirstRun = currentScreen.duringFirstRun,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                )
            }
            is LauncherScreen.LoggingSettings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.loggingSettingsHandler) }
                LoggingSettingsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.Permissions -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.permissionsHandler) }
                PermissionsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RetroAchievements -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val rows = dev.cannoli.scorza.ui.components.RaAccountRow.entries.toList()
                val selIdx = currentScreen.selectedIndex.coerceIn(0, rows.size - 1)
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.achievos_title),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    leftBottomItems = buildList {
                        if (rows[selIdx].isCycle) add(dev.cannoli.ui.DPAD_HORIZONTAL to stringResource(R.string.label_change))
                    },
                    rightBottomItems = buildList {
                        when (rows[selIdx]) {
                            dev.cannoli.scorza.ui.components.RaAccountRow.ACCOUNT ->
                                add(labels.confirm to stringResource(R.string.label_logout))
                            dev.cannoli.scorza.ui.components.RaAccountRow.OFFLINE_SETS ->
                                add(labels.confirm to stringResource(R.string.label_select))
                            dev.cannoli.scorza.ui.components.RaAccountRow.HARDCORE -> {}
                        }
                    },
                    buttonStyle = labels,
                ) {
                    List(
                        items = rows,
                        selectedIndex = selIdx,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged,
                    ) { _, row, isSelected ->
                        when (row) {
                            dev.cannoli.scorza.ui.components.RaAccountRow.ACCOUNT -> PillRowKeyValue(
                                label = currentScreen.username,
                                value = stringResource(dev.cannoli.scorza.ui.components.raTokenStatusRes(currentScreen.tokenState)),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            dev.cannoli.scorza.ui.components.RaAccountRow.HARDCORE -> PillRowKeyValue(
                                label = stringResource(R.string.achievos_account_row_hardcore),
                                value = stringResource(if (currentScreen.hardcore) R.string.value_on else R.string.value_off),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                            else -> PillRowText(
                                label = stringResource(row.labelRes),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                            )
                        }
                    }
                }
            }
            is LauncherScreen.RetroAchievementsOfflinePlatforms -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                dev.cannoli.scorza.ui.screens.RetroAchievementsOfflinePlatformsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged,
                )
            }
            is LauncherScreen.RetroAchievementsOfflineSets -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                dev.cannoli.scorza.ui.screens.RetroAchievementsOfflineSetsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged,
                )
            }
            is LauncherScreen.OnboardingWelcome -> {
                dev.cannoli.scorza.ui.screens.OnboardingWelcomeScreen(
                    mapping = onboardingMapping,
                    confirmPresses = onboardingConfirmPresses,
                    onRunExpired = onOnboardingRunExpired,
                )
            }
            is LauncherScreen.OnboardingPermissions -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingPermissionsHandler) }
                dev.cannoli.scorza.ui.screens.OnboardingPermissionsScreen(
                    screen = currentScreen,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.OnboardingStorage -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingStorageHandler) }
                dev.cannoli.scorza.ui.screens.OnboardingStorageScreen(
                    screen = currentScreen,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.SaveStatePicker -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveStatePickerHandler) }
                SaveStatePickerScreen(
                    rom = currentScreen.rom,
                    stateBasePath = currentScreen.stateBasePath,
                    slotOccupied = currentScreen.slotOccupied,
                    selectedSlotIndex = currentScreen.selectedSlotIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.SaveSlots -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveSlotsHandler) }
                SaveSlotsScreen(
                    gameName = currentScreen.displayName,
                    slots = currentScreen.slots,
                    selectedIndex = currentScreen.selectedIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    pendingDelete = currentScreen.pendingDelete,
                )
            }
            is LauncherScreen.GuidePicker -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
                GuidePickerScreen(
                    files = currentScreen.files,
                    selectedIndex = currentScreen.selectedIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.Guide -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
                val c = inputRouter?.guideHandler?.controller
                GuideViewerScreen(
                    filePath = currentScreen.filePath,
                    guideType = currentScreen.guideType,
                    page = currentScreen.page,
                    textZoom = currentScreen.textZoom,
                    initialScrollY = c?.guideInitialScroll?.intValue ?: 0,
                    initialScrollX = c?.guideInitialScrollX?.intValue ?: 0,
                    scrollDir = c?.guideScrollDir?.intValue ?: 0,
                    scrollXDir = c?.guideScrollXDir?.intValue ?: 0,
                    pageJump = c?.guidePageJump?.intValue ?: 0,
                    pageJumpDir = c?.guidePageJumpDir?.intValue ?: 0,
                    pageCount = c?.guidePageCount?.intValue ?: 0,
                    onScrollPosChanged = { y, x -> c?.onScrollChanged(y, x) },
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommPlatformList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val platforms = rommBrowseViewModel?.platforms?.collectAsState()?.value ?: emptyList()
                val collections = rommBrowseViewModel?.collections?.collectAsState()?.value ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    rommBrowseViewModel?.enterBrowse()
                }
                val showCollectionsRow = collections.isNotEmpty()
                val syncStatus = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                val syncProgress = rommBrowseViewModel?.syncProgress?.collectAsState()?.value
                var emptyMessage: String? = null
                var syncFraction: Float? = null
                if (platforms.isEmpty()) {
                    if (rommBrowseViewModel?.isServerUnsupported() == true) {
                        emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_server_too_old)
                    } else when (syncStatus) {
                        dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING ->
                            if (syncProgress != null && syncProgress.total > 0) {
                                val platformName = syncProgress.platform
                                emptyMessage = if (platformName != null)
                                    androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing_platform, platformName)
                                else androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                                syncFraction = syncProgress.completed.toFloat() / syncProgress.total
                            } else {
                                emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                            }
                        dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.ERROR ->
                            emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_sync_error)
                        else -> {}
                    }
                }
                val effectiveItemCount = platforms.size + (if (showCollectionsRow) 1 else 0)
                androidx.compose.runtime.LaunchedEffect(effectiveItemCount) {
                    if (currentScreen.itemCount != effectiveItemCount) nav?.replaceTop(currentScreen.copy(itemCount = effectiveItemCount))
                }
                dev.cannoli.scorza.ui.screens.RommPlatformListScreen(
                    platforms = platforms,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    showCollectionsRow = showCollectionsRow,
                    collectionCount = collections.size,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                    emptyMessage = emptyMessage,
                    progress = syncFraction,
                    syncing = syncStatus == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING,
                )
            }
            is LauncherScreen.RommGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.games?.collectAsState()?.value
                // null (not loaded), a different platform's id, or a stale search term means the rows are not
                // ours yet, so we render a loading blank rather than flashing the previous list/art or "No results".
                val loading = loaded?.id != currentScreen.platform.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id, currentScreen.search) {
                    rommBrowseViewModel?.openPlatform(currentScreen.platform, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                val loader = rommImageLoader
                val queueItems = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
                val doneForPlatform = queueItems.count {
                    it.tag == currentScreen.platform.cannoliTag &&
                        it.status == dev.cannoli.scorza.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(doneForPlatform) {
                    if (doneForPlatform > 0) rommBrowseViewModel?.refreshLocalState()
                }
                val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
                val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = currentScreen.platform.displayName,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        multiSelect = multiSelect,
                        checkedIds = checkedIds,
                        showFirmware = currentScreen.platform.firmwareCount > 0,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommCollectionGroups -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val counts = rommBrowseViewModel?.groupCounts?.collectAsState()?.value ?: emptyMap()
                val enabled = rommBrowseViewModel?.enabledGroups() ?: emptySet()
                val rows = listOf(
                    dev.cannoli.scorza.romm.RommCollectionGroup.USER to stringResource(dev.cannoli.ui.R.string.romm_collections_my),
                    dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL to stringResource(dev.cannoli.ui.R.string.romm_collections_virtual),
                    dev.cannoli.scorza.romm.RommCollectionGroup.SMART to stringResource(dev.cannoli.ui.R.string.romm_collections_smart),
                ).filter { it.first in enabled }
                 .map { dev.cannoli.scorza.ui.screens.RommGroupRow(it.first, it.second, counts[it.first] ?: 0) }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommCollectionGroupsScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommVirtualTypes -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val typeCounts = rommBrowseViewModel?.virtualTypeCounts?.collectAsState()?.value ?: emptyList()
                val rows = typeCounts.map { (type, count) ->
                    val label = dev.cannoli.scorza.romm.RommVirtualType.from(type)?.let { stringResource(it.labelRes) } ?: type
                    dev.cannoli.scorza.ui.screens.RommTypeRow(type, label, count)
                }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommVirtualTypesScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val key = currentScreen.group.name + (currentScreen.virtualType?.let { ":$it" } ?: "")
                val loaded = rommBrowseViewModel?.collectionList?.collectAsState()?.value
                val items = if (loaded?.id == key) loaded.rows else emptyList()
                androidx.compose.runtime.LaunchedEffect(key) {
                    rommBrowseViewModel?.openCollections(currentScreen.group, currentScreen.virtualType)
                }
                androidx.compose.runtime.LaunchedEffect(items.size) {
                    if (currentScreen.itemCount != items.size) nav?.replaceTop(currentScreen.copy(itemCount = items.size))
                }
                val title = currentScreen.virtualType
                    ?.let { vtype ->
                        val typeLabel = dev.cannoli.scorza.romm.RommVirtualType.from(vtype)?.let { t -> stringResource(t.labelRes) } ?: vtype
                        "${stringResource(dev.cannoli.ui.R.string.romm_collection_group_virtual)}: $typeLabel"
                    }
                    ?: stringResource(when (currentScreen.group) {
                        dev.cannoli.scorza.romm.RommCollectionGroup.USER -> dev.cannoli.ui.R.string.romm_collections_my
                        dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> dev.cannoli.ui.R.string.romm_collections_smart
                        dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> dev.cannoli.ui.R.string.romm_collections_virtual
                    })
                dev.cannoli.scorza.ui.screens.RommCollectionListScreen(
                    title = title,
                    collections = items,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.collectionGames?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.collection.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.collection.id, currentScreen.search) {
                    rommBrowseViewModel?.openCollection(currentScreen.collection, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                val queueItems = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
                val doneCount = queueItems.count {
                    it.status == dev.cannoli.scorza.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(doneCount) {
                    if (doneCount > 0) rommBrowseViewModel?.refreshCollectionLocalState()
                }
                val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
                val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommCollectionGameListScreen(
                        title = currentScreen.collection.name,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        multiSelect = multiSelect,
                        checkedIds = checkedIds,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommGlobalSearch -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.searchResults?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.term
                val results = if (loading) emptyList() else loaded?.rows ?: emptyList()
                val allPlatforms = rommBrowseViewModel?.allPlatforms?.collectAsState()?.value ?: emptyList()
                val platformTagById = remember(allPlatforms) { allPlatforms.associate { it.id to it.cannoliTag.uppercase() } }
                androidx.compose.runtime.LaunchedEffect(currentScreen.term) {
                    rommBrowseViewModel?.loadGlobalSearch(dev.cannoli.scorza.romm.RommSearchQuery(currentScreen.term))
                }
                androidx.compose.runtime.LaunchedEffect(loading, results.size) {
                    if (!loading && currentScreen.itemCount != results.size) nav?.replaceTop(currentScreen.copy(itemCount = results.size))
                }
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = stringResource(dev.cannoli.ui.R.string.romm_global_search_title),
                        search = currentScreen.term,
                        games = results,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                        platformLabelForGame = { g -> platformTagById[g.platformId] },
                    )
                }
            }
            is LauncherScreen.RommFirmwareList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id) {
                    if (currentScreen.loading) {
                        val result = runCatching {
                            rommBrowseViewModel?.loadFirmware(currentScreen.platform.id, currentScreen.platform.cannoliTag) ?: emptyList()
                        }
                        nav?.replaceTop(currentScreen.copy(
                            rows = result.getOrDefault(emptyList()),
                            loading = false,
                            error = result.isFailure,
                        ))
                    }
                }
                dev.cannoli.scorza.ui.screens.RommFirmwareListScreen(
                    title = stringResource(dev.cannoli.ui.R.string.romm_firmware_screen_title, currentScreen.platform.displayName),
                    rows = currentScreen.rows,
                    checkedIds = currentScreen.checkedIds,
                    loading = currentScreen.loading,
                    error = currentScreen.error,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommGameDetail -> {
                val loader = rommImageLoader
                val downloads = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
                val downloaded = downloads.any {
                    (it.payload as? dev.cannoli.scorza.romm.download.RommPayload)?.rommId == currentScreen.game.id && it.status == dev.cannoli.scorza.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(downloaded) {
                    if (downloaded && currentScreen.localState != dev.cannoli.scorza.romm.LocalState.PRESENT) {
                        nav?.replaceTop(currentScreen.copy(localState = dev.cannoli.scorza.romm.LocalState.PRESENT))
                    }
                }
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameDetailScreen(
                        game = currentScreen.game,
                        platformName = currentScreen.platformName,
                        localState = currentScreen.localState,
                        host = rommHost,
                        artType = rommArtType,
                        imageLoader = loader,
                        scrollStep = currentScreen.scrollStep,
                        onScrollStepChanged = { nav?.replaceTop(currentScreen.copy(scrollStep = it)) },
                        memberCount = currentScreen.versionCount,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        buttonStyle = labels,
                    )
                }
            }
        }

        // Hoisted full-screen dialog rendering: every screen gets the keyboard / full-screen
        // overlays for free, so a new screen can never silently capture input with nothing drawn.
        val overlayDownloads = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
        if (dialog is DialogState.RommDownloads && overlayDownloads.isEmpty()) {
            androidx.compose.runtime.LaunchedEffect(Unit) { nav?.dialogState?.value = DialogState.None }
        }
        val artState = rommArtFetcher?.state?.collectAsState()?.value
        androidx.compose.runtime.LaunchedEffect(artState) {
            val finished = artState as? dev.cannoli.scorza.romm.art.ArtFetchState.Finished
            if (finished != null && dialog !is DialogState.RommArtResults) {
                nav?.dialogState?.value = DialogState.RommArtResults(finished.results)
            }
        }
        // No allowlist gate here on purpose: DialogOverlay's own when decides what it draws and
        // falls through to nothing for states a screen renders itself. A second list to keep in
        // sync is how a dialog ends up set and consuming input while drawing nothing.
        DialogOverlay(
            dialogState = dialog,
            backgroundImagePath = appSettings.backgroundImagePath,
            backgroundTint = appSettings.backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            downloadProgress = downloadProgress,
            coreUpdate = coreUpdate,
            downloadError = downloadError,
            downloads = overlayDownloads,
            updateAvailable = updateAvailable,
            buttonStyle = labels,
            appListPlatformTag = gameListViewModel?.state?.collectAsState()?.value?.platformTag,
        )

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is DialogState.UpdateDownload
                || dialog is DialogState.Launching
                || dialog is DialogState.SaveSyncChecking
                || dialog is KeyboardHost
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.CreditsSection
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.Guide
                || currentScreen is LauncherScreen.InputTester
                || currentScreen is LauncherScreen.OnboardingScreen
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val showKitchenIcon = dev.cannoli.scorza.server.KitchenManager.running.collectAsState().value
                && appSettings.showKitchen
        val artRunning = appSettings.showDownloads &&
                rommArtFetcher?.state?.collectAsState()?.value == dev.cannoli.scorza.romm.art.ArtFetchState.Running
        val activeDownloadCount = if (appSettings.showDownloads) {
            rommDownloader?.state?.collectAsState()?.value?.count {
                it.status == dev.cannoli.scorza.download.DownloadStatus.Queued || it.status is dev.cannoli.scorza.download.DownloadStatus.Downloading
            } ?: 0
        } else 0
        val inRomm = currentScreen is LauncherScreen.RommPlatformList ||
                currentScreen is LauncherScreen.RommGameList ||
                currentScreen is LauncherScreen.RommCollectionGroups ||
                currentScreen is LauncherScreen.RommVirtualTypes ||
                currentScreen is LauncherScreen.RommCollectionList ||
                currentScreen is LauncherScreen.RommCollectionGameList ||
                currentScreen is LauncherScreen.RommGlobalSearch ||
                currentScreen is LauncherScreen.RommFirmwareList ||
                currentScreen is LauncherScreen.RommGameDetail
        val cacheSyncStatus = rommCacheSyncIndicator(
            status = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                ?: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.IDLE,
            stale = rommBrowseViewModel?.syncStale?.collectAsState()?.value ?: false,
            inRomm = inRomm,
        )
        val hasContent = showKitchenIcon
                || activeDownloadCount > 0
                || artRunning
                || cacheSyncStatus != RommCacheSyncStatus.IDLE
                || appSettings.showWifi
                || appSettings.showBluetooth
                || appSettings.showVpn
                || appSettings.showClock
                || appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE
                || (updateAvailable && appSettings.showUpdate)
        if (!hideForDialog && !hideForScreen && hasContent) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(screenInsets())
                .onGloballyPositioned { coords ->
                    statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                }
        ) {
            StatusBar(
                updateAvailable = updateAvailable,
                kitchenRunning = showKitchenIcon,
                downloadCount = activeDownloadCount,
                downloadsActive = artRunning,
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showVpn = appSettings.showVpn,
                showClock = appSettings.showClock,
                showBattery = appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE,
                batteryIconOnly = appSettings.batteryDisplay == dev.cannoli.scorza.settings.BatteryDisplay.ICON,
                showUpdate = appSettings.showUpdate,
                use24hTime = appSettings.use24h,
                saveSyncStatus = saveSyncStatus,
                rommCacheSyncStatus = cacheSyncStatus,
            )
        }
        }
        if (inRomm) RommBorderFrame()
    }
    val onScreenGeometryRow = currentScreen is LauncherScreen.Settings && settingsState.activeCategory == "screen_geometry"
    val geometryIsDefault = appSettings.screenGeometryWidth == 100 && appSettings.screenGeometryHeight == 100 &&
        appSettings.screenGeometryX == 0 && appSettings.screenGeometryY == 0
    if (onScreenGeometryRow && !geometryIsDefault) {
        Box(modifier = Modifier.fillMaxSize().border(2.dp, cannoliColors.accent))
    }
    OsdHost(controller = osdController)
    }
    }
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    }
    }
}
