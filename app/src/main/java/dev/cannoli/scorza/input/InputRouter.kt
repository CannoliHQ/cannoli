package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.screen.GameListInputHandler
import dev.cannoli.scorza.input.screen.InputTesterInputHandler
import dev.cannoli.scorza.input.screen.ScrollListInputHandler
import dev.cannoli.scorza.input.screen.SettingsInputHandler
import dev.cannoli.scorza.input.screen.SetupInputHandler
import dev.cannoli.scorza.input.screen.SystemListInputHandler
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.ui.components.CREDITS
import dev.cannoli.scorza.ui.screens.DialogState
import javax.inject.Inject

@ActivityScoped
class InputRouter @Inject constructor(
    private val nav: NavigationController,
    private val dialogHandler: DialogInputHandler,
    private val systemListHandler: SystemListInputHandler,
    private val gameListHandler: GameListInputHandler,
    private val settingsHandler: SettingsInputHandler,
    private val setupHandler: SetupInputHandler,
    private val inputTesterHandler: InputTesterInputHandler,
    private val scrollListFactory: ScrollListInputHandler.Factory,
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val profileManager: ProfileManager,
    private val globalOverrides: GlobalOverridesManager,
    private val launcherActions: LauncherActions,
    @ApplicationContext private val context: Context,
) {
    var cancelShortcutListening: () -> Unit = {}
    var startControlListening: () -> Unit = {}
    var unregisterCoreQueryReceiver: () -> Unit = {}
    var controlButtons: List<dev.cannoli.scorza.libretro.LibretroInput.ButtonDef> = emptyList()

    fun wire(inputHandler: InputHandler) {
        inputHandler.onUp    = { if (!dialogHandler.onUp())     currentHandler().onUp() }
        inputHandler.onDown  = { if (!dialogHandler.onDown())   currentHandler().onDown() }
        inputHandler.onLeft  = { if (!dialogHandler.onLeft())   currentHandler().onLeft() }
        inputHandler.onRight = { if (!dialogHandler.onRight())  currentHandler().onRight() }
        inputHandler.onConfirm = { if (!dialogHandler.onConfirm()) currentHandler().onConfirm() }
        inputHandler.onBack  = { if (!dialogHandler.onBack())   currentHandler().onBack() }
        inputHandler.onStart = { if (!dialogHandler.onStart())  currentHandler().onStart() }
        inputHandler.onSelect = { if (!dialogHandler.onSelect()) currentHandler().onSelect() }
        inputHandler.onNorth = { if (!dialogHandler.onNorth())  currentHandler().onNorth() }
        inputHandler.onWest  = { if (!dialogHandler.onWest())   currentHandler().onWest() }
        inputHandler.onL1    = { if (!dialogHandler.onL1())     currentHandler().onL1() }
        inputHandler.onR1    = { if (!dialogHandler.onR1())     currentHandler().onR1() }
        inputHandler.onL2    = { if (!dialogHandler.onL2())     currentHandler().onL2() }
        inputHandler.onR2    = { if (!dialogHandler.onR2())     currentHandler().onR2() }
    }

    fun onSelectUp() {
        dialogHandler.cancelSelectHold()
        gameListHandler.cancelSelectHoldTimer()

        val dialogConsumed = dialogHandler.onSelectUp()
        if (!dialogConsumed) currentHandler().onSelectUp()

        nav.selectDown = false
        nav.selectHeld = false
    }

    private fun currentHandler(): ScreenInputHandler = when (val screen = nav.currentScreen) {
        LauncherScreen.SystemList -> systemListHandler
        LauncherScreen.GameList   -> gameListHandler
        LauncherScreen.Settings   -> settingsHandler
        is LauncherScreen.Setup,
        is LauncherScreen.Installing,
        is LauncherScreen.Housekeeping -> setupHandler
        is LauncherScreen.DirectoryBrowser -> setupHandler
        LauncherScreen.InputTester -> inputTesterHandler
        is LauncherScreen.CoreMapping -> scrollListFactory.create(
            itemCount = { screen.mappings.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.CoreMapping)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CoreMapping)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.CoreMapping ?: return@create
                s.mappings.getOrNull(s.selectedIndex)?.let { entry ->
                    val bundledCoresDir = LaunchManager.extractBundledCores(context)
                    val options = platformConfig.getCorePickerOptions(
                        entry.tag, context.packageManager,
                        installedRaCores = installedCoreService.installedCores,
                        embeddedCoresDir = bundledCoresDir,
                        unresponsivePackages = installedCoreService.unresponsivePackages
                    )
                    val currentCore = platformConfig.getCoreMapping(entry.tag)
                    val currentApp = platformConfig.getAppPackage(entry.tag)
                    val currentRunner = entry.runnerLabel
                    val selectedIdx = if ((currentRunner == "App" || currentRunner == "Standalone") && currentApp != null) {
                        options.indexOfFirst { it.appPackage == currentApp }.coerceAtLeast(0)
                    } else {
                        options.indexOfFirst { it.coreId == currentCore && it.runnerLabel == currentRunner }
                            .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                    }
                    nav.push(LauncherScreen.CorePicker(
                        tag = entry.tag,
                        platformName = entry.platformName,
                        cores = options,
                        selectedIndex = selectedIdx,
                        activeIndex = selectedIdx
                    ))
                }
            },
            onBack = {
                platformConfig.saveCoreMappings()
                nav.pop()
            },
            onStart = {
                val s = nav.currentScreen as? LauncherScreen.CoreMapping ?: return@create
                val newFilter = (s.filter + 1) % 4
                nav.replaceTop(s.copy(
                    mappings = filterCoreMappings(s.allMappings, newFilter),
                    filter = newFilter, selectedIndex = 0, scrollTarget = 0
                ))
            },
        )
        is LauncherScreen.CorePicker -> scrollListFactory.create(
            itemCount = { screen.cores.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.CorePicker)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CorePicker)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.CorePicker ?: return@create
                dialogHandler.onCorePickerConfirm(s)
            },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.CorePicker ?: run { nav.pop(); return@create }
                nav.pop()
                if (s.gamePath != null) {
                    dialogHandler.restoreContextMenu()
                } else {
                    val cm = nav.screenStack.lastOrNull()
                    if (cm is LauncherScreen.CoreMapping) {
                        val all = platformConfig.getDetailedMappings(context.packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(context), installedCoreService.unresponsivePackages)
                        val filtered = filterCoreMappings(all, cm.filter)
                        val idx = filtered.indexOfFirst { it.tag == s.tag }.coerceAtLeast(0)
                        nav.screenStack[nav.screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                    }
                }
            },
            onStart = null,
        )
        is LauncherScreen.ColorList -> scrollListFactory.create(
            itemCount = { screen.colors.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.ColorList)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ColorList)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.ColorList ?: return@create
                s.colors.getOrNull(s.selectedIndex)?.let { entry -> launcherActions.openColorPicker(entry.key) }
            },
            onBack = { nav.pop() },
            onStart = null,
        )
        is LauncherScreen.CollectionPicker -> scrollListFactory.create(
            itemCount = { screen.collections.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.CollectionPicker)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.CollectionPicker)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: return@create
                if (s.collections.isNotEmpty()) {
                    val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                    nav.replaceTop(s.copy(checkedIndices = newChecked))
                }
            },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: run { nav.pop(); return@create }
                dialogHandler.onCollectionPickerConfirm(s)
            },
            onStart = {
                val s = nav.currentScreen as? LauncherScreen.CollectionPicker ?: return@create
                nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = s.gamePaths)
            },
        )
        is LauncherScreen.ChildPicker -> scrollListFactory.create(
            itemCount = { screen.collections.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.ChildPicker)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ChildPicker)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.ChildPicker ?: return@create
                if (s.collections.isNotEmpty()) {
                    val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                    nav.replaceTop(s.copy(checkedIndices = newChecked))
                }
            },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.ChildPicker ?: run { nav.pop(); return@create }
                dialogHandler.onChildPickerConfirm(s)
            },
            onStart = null,
        )
        is LauncherScreen.AppPicker -> scrollListFactory.create(
            itemCount = { screen.apps.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.AppPicker)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.AppPicker)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.AppPicker ?: return@create
                val newChecked = if (s.selectedIndex in s.checkedIndices) s.checkedIndices - s.selectedIndex else s.checkedIndices + s.selectedIndex
                nav.replaceTop(s.copy(checkedIndices = newChecked))
            },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.AppPicker ?: run { nav.pop(); return@create }
                settingsHandler.confirmAppPicker(s)
            },
            onStart = null,
        )
        is LauncherScreen.ProfileList -> scrollListFactory.create(
            itemCount = { screen.profiles.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.ProfileList)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ProfileList)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.ProfileList ?: return@create
                val name = s.profiles.getOrNull(s.selectedIndex)
                if (name != null) {
                    nav.push(LauncherScreen.ControlBinding(controls = profileManager.readControls(name), profileName = name))
                }
            },
            onBack = { nav.pop() },
            onStart = {
                val s = nav.currentScreen as? LauncherScreen.ProfileList ?: return@create
                val name = s.profiles.getOrNull(s.selectedIndex)
                if (name != null && !ProfileManager.isProtected(name)) {
                    nav.dialogState.value = DialogState.ContextMenu(gameName = name, options = listOf("Rename", "Delete"))
                }
            },
        )
        is LauncherScreen.ControlBinding -> scrollListFactory.create(
            itemCount = { controlButtons.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.ControlBinding)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.ControlBinding)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = { startControlListening() },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.ControlBinding ?: run { nav.pop(); return@create }
                profileManager.saveControls(s.profileName, s.controls)
                nav.pop()
                val prev = nav.screenStack.lastOrNull()
                if (prev is LauncherScreen.ProfileList) {
                    nav.screenStack[nav.screenStack.lastIndex] = prev.copy(profiles = profileManager.listProfiles())
                }
            },
            onStart = {
                val s = nav.currentScreen as? LauncherScreen.ControlBinding ?: return@create
                if (s.listeningIndex < 0) {
                    val btn = controlButtons.getOrNull(s.selectedIndex)
                    if (btn != null && btn.prefKey != "btn_menu") {
                        val currentKeyCode = s.controls[btn.prefKey] ?: btn.defaultKeyCode
                        if (currentKeyCode != dev.cannoli.scorza.libretro.LibretroInput.UNMAPPED) {
                            nav.replaceTop(s.copy(controls = s.controls + (btn.prefKey to dev.cannoli.scorza.libretro.LibretroInput.UNMAPPED)))
                        }
                    }
                }
            },
        )
        is LauncherScreen.ShortcutBinding -> scrollListFactory.create(
            itemCount = { ShortcutAction.entries.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.ShortcutBinding)?.selectedIndex ?: 0 },
            onMove = { idx ->
                val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@create
                if (!s.listening) nav.replaceTop(s.copy(selectedIndex = idx))
            },
            onConfirm = {
                val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@create
                if (!s.listening) {
                    nav.replaceTop(s.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                }
            },
            onBack = {
                val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: run { nav.pop(); return@create }
                cancelShortcutListening()
                globalOverrides.saveShortcuts(s.shortcuts)
                nav.pop()
            },
            onStart = {
                val s = nav.currentScreen as? LauncherScreen.ShortcutBinding ?: return@create
                if (!s.listening) {
                    ShortcutAction.entries.getOrNull(s.selectedIndex)?.let { action ->
                        nav.replaceTop(s.copy(shortcuts = s.shortcuts + (action to emptySet())))
                    }
                }
            },
        )
        is LauncherScreen.Credits -> scrollListFactory.create(
            itemCount = { CREDITS.size },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.Credits)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.Credits)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {},
            onBack = { nav.pop() },
            onStart = null,
        )
        is LauncherScreen.InstalledCores -> scrollListFactory.create(
            itemCount = { (nav.currentScreen as? LauncherScreen.InstalledCores)?.cores?.size ?: 0 },
            selectedIndex = { (nav.currentScreen as? LauncherScreen.InstalledCores)?.selectedIndex ?: 0 },
            onMove = { idx -> nav.replaceTop((nav.currentScreen as? LauncherScreen.InstalledCores)?.copy(selectedIndex = idx) ?: return@create) },
            onConfirm = {},
            onBack = {
                unregisterCoreQueryReceiver()
                nav.pop()
            },
            onStart = null,
        )
        else -> object : ScreenInputHandler {}
    }

    private fun filterCoreMappings(all: List<dev.cannoli.scorza.ui.screens.CoreMappingEntry>, filter: Int): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
    }
}
