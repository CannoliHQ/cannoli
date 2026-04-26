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
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.ui.components.CREDITS
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
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
    var controlButtons: List<LibretroInput.ButtonDef> = emptyList()

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
        LauncherScreen.SystemList  -> systemListHandler
        LauncherScreen.GameList    -> gameListHandler
        LauncherScreen.Settings    -> settingsHandler
        LauncherScreen.InputTester -> inputTesterHandler
        is LauncherScreen.Setup,
        is LauncherScreen.Installing,
        is LauncherScreen.Housekeeping,
        is LauncherScreen.DirectoryBrowser -> setupHandler
        is LauncherScreen.ScrollableScreen -> scrollableHandlerFor(screen)
        else -> object : ScreenInputHandler {}
    }

    /**
     * Per-screen handler factory for everything that implements [LauncherScreen.ScrollableScreen].
     *
     * The actual logic lives in the named *Handler() methods below; each one is a thin wrapper
     * around [scrollable], which buries the [nav.currentScreen] cast and the default move/back
     * boilerplate so each screen only has to spell out the parts that differ.
     */
    private fun scrollableHandlerFor(screen: LauncherScreen.ScrollableScreen): ScreenInputHandler = when (screen) {
        is LauncherScreen.CoreMapping       -> coreMappingHandler()
        is LauncherScreen.CorePicker        -> corePickerHandler()
        is LauncherScreen.ColorList         -> colorListHandler()
        is LauncherScreen.CollectionPicker  -> collectionPickerHandler()
        is LauncherScreen.ChildPicker       -> childPickerHandler()
        is LauncherScreen.AppPicker         -> appPickerHandler()
        is LauncherScreen.ProfileList       -> profileListHandler()
        is LauncherScreen.ControlBinding    -> controlBindingHandler()
        is LauncherScreen.ShortcutBinding   -> shortcutBindingHandler()
        is LauncherScreen.Credits           -> creditsHandler()
        is LauncherScreen.InstalledCores    -> installedCoresHandler()
        else -> object : ScreenInputHandler {}
    }

    private inline fun <reified T> scrollable(
        crossinline onConfirm: T.() -> Unit = {},
        crossinline onBack: T.() -> Unit = { nav.pop() },
        crossinline onMove: T.(Int) -> LauncherScreen = { idx -> withScroll(selectedIndex = idx, scrollTarget = scrollTarget) },
        noinline onStart: (T.() -> Unit)? = null,
        noinline onWest: (T.() -> Unit)? = null,
    ): ScrollListInputHandler where T : LauncherScreen, T : LauncherScreen.ScrollableScreen {
        val current: () -> T? = { nav.currentScreen as? T }
        return scrollListFactory.create(
            itemCount = { current()?.itemCount ?: 0 },
            selectedIndex = { current()?.selectedIndex ?: 0 },
            onMove = { idx -> current()?.let { nav.replaceTop(it.onMove(idx)) } ?: Unit },
            onConfirm = { current()?.onConfirm() ?: Unit },
            onBack = { current()?.onBack() ?: nav.pop() },
            onStart = onStart?.let { fn -> { current()?.fn() ?: Unit } },
            onWest = onWest?.let { fn -> { current()?.fn() ?: Unit } },
        )
    }

    private fun coreMappingHandler() = scrollable<LauncherScreen.CoreMapping>(
        onConfirm = {
            mappings.getOrNull(selectedIndex)?.let { entry ->
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
        onWest = {
            val newFilter = (filter + 1) % 4
            nav.replaceTop(copy(
                mappings = filterCoreMappings(allMappings, newFilter),
                filter = newFilter, selectedIndex = 0, scrollTarget = 0
            ))
        },
    )

    private fun corePickerHandler() = scrollable<LauncherScreen.CorePicker>(
        onConfirm = { dialogHandler.onCorePickerConfirm(this) },
        onBack = {
            val s = this
            nav.pop()
            if (s.gamePath != null) {
                dialogHandler.restoreContextMenu()
            } else {
                val cm = nav.screenStack.lastOrNull()
                if (cm is LauncherScreen.CoreMapping) {
                    val all = platformConfig.getDetailedMappings(
                        context.packageManager,
                        installedCoreService.installedCores,
                        LaunchManager.extractBundledCores(context),
                        installedCoreService.unresponsivePackages
                    )
                    val filtered = filterCoreMappings(all, cm.filter)
                    val idx = filtered.indexOfFirst { it.tag == s.tag }.coerceAtLeast(0)
                    nav.screenStack[nav.screenStack.lastIndex] =
                        cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                }
            }
        },
    )

    private fun colorListHandler() = scrollable<LauncherScreen.ColorList>(
        onConfirm = { colors.getOrNull(selectedIndex)?.let { launcherActions.openColorPicker(it.key) } },
    )

    private fun collectionPickerHandler() = scrollable<LauncherScreen.CollectionPicker>(
        onConfirm = {
            if (collections.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onCollectionPickerConfirm(this) },
        onStart = { nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = gamePaths) },
    )

    private fun childPickerHandler() = scrollable<LauncherScreen.ChildPicker>(
        onConfirm = {
            if (collections.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onChildPickerConfirm(this) },
    )

    private fun appPickerHandler() = scrollable<LauncherScreen.AppPicker>(
        onConfirm = {
            val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                             else checkedIndices + selectedIndex
            nav.replaceTop(copy(checkedIndices = newChecked))
        },
        onBack = { settingsHandler.confirmAppPicker(this) },
    )

    private fun profileListHandler() = scrollable<LauncherScreen.ProfileList>(
        onConfirm = {
            profiles.getOrNull(selectedIndex)?.let { name ->
                nav.push(LauncherScreen.ControlBinding(controls = profileManager.readControls(name), profileName = name))
            }
        },
        onStart = {
            profiles.getOrNull(selectedIndex)?.let { name ->
                if (!ProfileManager.isProtected(name)) {
                    nav.dialogState.value = DialogState.ContextMenu(gameName = name, options = listOf("Rename", "Delete"))
                }
            }
        },
    )

    private fun controlBindingHandler() = scrollable<LauncherScreen.ControlBinding>(
        onConfirm = { startControlListening() },
        onBack = {
            profileManager.saveControls(profileName, controls)
            nav.pop()
            val prev = nav.screenStack.lastOrNull()
            if (prev is LauncherScreen.ProfileList) {
                nav.screenStack[nav.screenStack.lastIndex] = prev.copy(profiles = profileManager.listProfiles())
            }
        },
        onStart = {
            if (listeningIndex < 0) {
                val btn = controlButtons.getOrNull(selectedIndex)
                if (btn != null && btn.prefKey != "btn_menu") {
                    val currentKeyCode = controls[btn.prefKey] ?: btn.defaultKeyCode
                    if (currentKeyCode != LibretroInput.UNMAPPED) {
                        nav.replaceTop(copy(controls = controls + (btn.prefKey to LibretroInput.UNMAPPED)))
                    }
                }
            }
        },
    )

    private fun shortcutBindingHandler() = scrollable<LauncherScreen.ShortcutBinding>(
        onMove = { idx -> if (listening) this else copy(selectedIndex = idx) },
        onConfirm = {
            if (!listening) nav.replaceTop(copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
        },
        onBack = {
            cancelShortcutListening()
            globalOverrides.saveShortcuts(shortcuts)
            nav.pop()
        },
        onStart = {
            if (!listening) {
                ShortcutAction.entries.getOrNull(selectedIndex)?.let { action ->
                    nav.replaceTop(copy(shortcuts = shortcuts + (action to emptySet())))
                }
            }
        },
    )

    private fun creditsHandler() = scrollable<LauncherScreen.Credits>()

    private fun installedCoresHandler() = scrollable<LauncherScreen.InstalledCores>(
        onBack = {
            unregisterCoreQueryReceiver()
            nav.pop()
        },
    )

    private fun filterCoreMappings(all: List<CoreMappingEntry>, filter: Int): List<CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
    }
}
