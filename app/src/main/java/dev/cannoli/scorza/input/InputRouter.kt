package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.screen.ControllerDetailInputHandler
import dev.cannoli.scorza.input.screen.ControllersInputHandler
import dev.cannoli.scorza.input.screen.EditButtonsInputHandler
import dev.cannoli.scorza.input.screen.GameListInputHandler
import dev.cannoli.scorza.input.screen.LoggingSettingsInputHandler
import dev.cannoli.scorza.input.screen.InputTesterInputHandler
import dev.cannoli.scorza.input.screen.ScrollListInputHandler
import dev.cannoli.scorza.input.screen.SettingsInputHandler
import dev.cannoli.scorza.input.screen.OnboardingInputHandler
import dev.cannoli.scorza.input.screen.SaveStatePickerInputHandler
import dev.cannoli.scorza.input.screen.SystemListInputHandler
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.input.runtime.InputDispatcher
import javax.inject.Inject

@ActivityScoped
class InputRouter @Inject constructor(
    private val nav: NavigationController,
    private val dialogHandler: DialogInputHandler,
    val systemListHandler: SystemListInputHandler,
    val gameListHandler: GameListInputHandler,
    val settingsHandler: SettingsInputHandler,
    val onboardingHandler: OnboardingInputHandler,
    val inputTesterHandler: InputTesterInputHandler,
    val saveStatePickerHandler: SaveStatePickerInputHandler,
    val controllerDetailHandler: ControllerDetailInputHandler,
    val controllersHandler: ControllersInputHandler,
    val editButtonsHandler: EditButtonsInputHandler,
    val loggingSettingsHandler: LoggingSettingsInputHandler,
    private val scrollListFactory: ScrollListInputHandler.Factory,
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val emulatorMappingBuilder: EmulatorMappingBuilder,
    private val globalOverrides: GlobalOverridesManager,
    private val launcherActions: LauncherActions,
    private val bindingController: BindingController,
    private val screenInputRegistry: dev.cannoli.scorza.input.runtime.ScreenInputRegistry,
    @ApplicationContext private val context: Context,
) {
    var unregisterCoreQueryReceiver: () -> Unit = {}

    fun wire(dispatcher: InputDispatcher) {
        gameListHandler.buildContextOptions = dialogHandler::buildGameContextOptions

        // Launcher overrides onSelectUp because the select-hold cancel + nav-flag reset is
        // specific to launcher state; the generic helper cannot know about it.
        // Resolve dedicated screen handlers synchronously from nav state so input is not lost in
        // the recompose gap when a screen is pushed off the input cadence (e.g. the save-state
        // picker from a hold timer). Scrollable screens keep their registry-backed instances. The
        // IGM wires without a resolver, which resets this so its registry-top dispatch is restored.
        dispatcher.wireToRegistry(
            dialogHandler = dialogHandler,
            onSelectUpOverride = { onSelectUp() },
            screenResolver = { activeScreenHandler() },
        )
    }

    private fun activeScreenHandler(): ScreenInputHandler =
        dedicatedHandlerFor(nav.currentScreen) ?: screenInputRegistry.top

    private fun dedicatedHandlerFor(screen: LauncherScreen): ScreenInputHandler? = when (screen) {
        is LauncherScreen.SystemList -> systemListHandler
        is LauncherScreen.GameList -> gameListHandler
        is LauncherScreen.Settings -> settingsHandler
        is LauncherScreen.InputTester -> inputTesterHandler
        is LauncherScreen.SaveStatePicker -> saveStatePickerHandler
        is LauncherScreen.Controllers -> controllersHandler
        is LauncherScreen.ControllerDetail -> controllerDetailHandler
        is LauncherScreen.EditButtons -> editButtonsHandler
        is LauncherScreen.LoggingSettings -> loggingSettingsHandler
        is LauncherScreen.OnboardingPermissions -> onboardingHandler
        is LauncherScreen.DirectoryBrowser -> onboardingHandler
        else -> null
    }

    fun onSelectUp() {
        dialogHandler.cancelSelectHold()
        gameListHandler.cancelSelectHoldTimer()

        val dialogConsumed = dialogHandler.onSelectUp()
        if (!dialogConsumed) activeScreenHandler().onSelectUp()

        nav.selectDown = false
        nav.selectHeld = false
    }

    /**
     * Returns the appropriate handler for a ScrollableScreen instance. Used by [AppNavGraph] to
     * push a handler via ScreenInput for screens that don't have a dedicated handler class --
     * the handler is generated inline from the [scrollable] factory each time the screen is
     * composed. Other screens (with dedicated handler classes like [SystemListInputHandler])
     * push their handler directly via this router's public fields, bypassing this method.
     */
    fun currentHandler(): ScreenInputHandler = when (val screen = nav.currentScreen) {
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
        is LauncherScreen.EmulatorMapping     -> emulatorMappingHandler()
        is LauncherScreen.EmulatorPicker      -> emulatorPickerHandler()
        is LauncherScreen.PlatformDetail      -> platformDetailHandler()
        is LauncherScreen.EmulatorSourcePicker -> emulatorSourcePickerHandler()
        is LauncherScreen.PlatformOverrides   -> platformOverridesHandler()
        is LauncherScreen.ColorList         -> colorListHandler()
        is LauncherScreen.CollectionPicker  -> collectionPickerHandler()
        is LauncherScreen.ChildPicker       -> childPickerHandler()
        is LauncherScreen.AppPicker         -> appPickerHandler()
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
        noinline onNorth: (T.() -> Unit)? = null,
        noinline onLeft: (T.() -> Unit)? = null,
        noinline onRight: (T.() -> Unit)? = null,
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
            onNorth = onNorth?.let { fn -> { current()?.fn() ?: Unit } },
            onLeft = onLeft?.let { fn -> { current()?.fn() ?: Unit } },
            onRight = onRight?.let { fn -> { current()?.fn() ?: Unit } },
        )
    }

    private fun emulatorMappingHandler() = scrollable<LauncherScreen.EmulatorMapping>(
        onConfirm = {
            val entry = mappings.getOrNull(selectedIndex) ?: return@scrollable
            nav.push(emulatorMappingBuilder.buildPlatformDetail(entry.tag, entry.platformName))
        },
        onBack = { nav.pop() },
        onWest = {
            val newFilter = (filter + 1) % 3
            nav.replaceTop(copy(
                mappings = emulatorMappingBuilder.filter(allMappings, newFilter),
                filter = newFilter, selectedIndex = 0, scrollTarget = 0
            ))
        },
    )

    private fun emulatorPickerHandler() = scrollable<LauncherScreen.EmulatorPicker>(
        onConfirm = { dialogHandler.onEmulatorPickerConfirm(this) },
        onBack = {
            val s = this
            nav.pop()
            if (s.gamePath != null) {
                dialogHandler.restoreContextMenu()
            } else {
                val cm = nav.screenStack.lastOrNull()
                if (cm is LauncherScreen.EmulatorMapping) {
                    val all = emulatorMappingBuilder.detailedMappings()
                    val filtered = emulatorMappingBuilder.filter(all, cm.filter)
                    val idx = filtered.indexOfFirst { it.tag == s.tag }.coerceAtLeast(0)
                    nav.screenStack[nav.screenStack.lastIndex] =
                        cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                }
            }
        },
    )

    private fun platformDetailHandler() = scrollable<LauncherScreen.PlatformDetail>(
        onConfirm = {
            when (selectedIndex) {
                1 -> {
                    if (emulatorRowPickable && currentSource != null) {
                        val bundled = LaunchManager.extractBundledCores(context)
                        val installed = platformConfig.emulatorOptionsForSource(
                            tag = tag, source = currentSource, includeAll = false,
                            installedRaCores = installedCoreService.configuredCores(),
                            embeddedCoresDir = bundled, pm = context.packageManager,
                        )
                        val raUnresponsive = currentSource == dev.cannoli.scorza.config.EmulatorSource.RetroArch &&
                            platformConfig.isRetroArchUnresponsive(
                                tag, installedCoreService.configuredCores(), installedCoreService.configuredUnresponsive()
                            )
                        val startShowAll = installed.isEmpty() || raUnresponsive
                        val options = if (startShowAll) {
                            platformConfig.emulatorOptionsForSource(
                                tag = tag, source = currentSource, includeAll = true,
                                installedRaCores = installedCoreService.configuredCores(),
                                embeddedCoresDir = bundled, pm = context.packageManager,
                            )
                        } else installed
                        nav.push(LauncherScreen.EmulatorSourcePicker(
                            tag = tag,
                            platformName = platformName,
                            source = currentSource,
                            options = options,
                            showAll = startShowAll,
                            raUnresponsive = raUnresponsive,
                        ))
                    }
                }
                3 -> {
                    val list = platformConfig.getPlatformOverrides(tag)
                    nav.push(LauncherScreen.PlatformOverrides(
                        tag = tag,
                        platformName = platformName,
                        overrides = list,
                    ))
                }
                else -> Unit
            }
        },
        onBack = { nav.pop() },
        onStart = {
            val pick = pendingPick
            if (pick != null) {
                if (pick.appPackage != null) {
                    platformConfig.setAppMapping(tag, pick.appPackage)
                } else {
                    platformConfig.setCoreMapping(tag, pick.coreId, pick.runnerLabel)
                }
                platformConfig.saveCoreMappings()
            }
            nav.pop()
            refreshEmulatorMappingOnStack()
        },
        onNorth = {
            if (resettable) {
                nav.dialogState.value = DialogState.PlatformResetConfirm(tag = tag, platformName = platformName)
            }
        },
        onLeft = {
            if (selectedIndex == 0 && availableSources.size > 1) {
                val idx = availableSources.indexOf(currentSource).let { if (it < 0) 0 else it }
                val newIdx = (idx - 1 + availableSources.size) % availableSources.size
                nav.replaceTop(rebuildForSource(this, availableSources[newIdx]))
            }
        },
        onRight = {
            if (selectedIndex == 0 && availableSources.size > 1) {
                val idx = availableSources.indexOf(currentSource).let { if (it < 0) 0 else it }
                val newIdx = (idx + 1) % availableSources.size
                nav.replaceTop(rebuildForSource(this, availableSources[newIdx]))
            }
        },
    )

    private fun emulatorSourcePickerHandler() = scrollable<LauncherScreen.EmulatorSourcePicker>(
        onConfirm = {
            if (options.isNotEmpty()) {
                dialogHandler.onEmulatorSourcePickerConfirm(this)
            }
        },
        onBack = { nav.pop() },
        onNorth = {
            val bundled = LaunchManager.extractBundledCores(context)
            val newOptions = platformConfig.emulatorOptionsForSource(
                tag = tag, source = source, includeAll = !showAll,
                installedRaCores = installedCoreService.configuredCores(),
                embeddedCoresDir = bundled, pm = context.packageManager,
            )
            nav.replaceTop(copy(showAll = !showAll, options = newOptions, selectedIndex = 0, scrollTarget = 0))
        },
    )

    private fun platformOverridesHandler() = scrollable<LauncherScreen.PlatformOverrides>(
        onConfirm = {
            val entry = overrides.getOrNull(selectedIndex) ?: return@scrollable
            platformConfig.clearGameOverride(entry.first)
            val refreshed = platformConfig.getPlatformOverrides(tag)
            if (refreshed.isEmpty()) {
                nav.pop()
                val detail = nav.screenStack.lastOrNull() as? LauncherScreen.PlatformDetail
                if (detail != null) nav.screenStack[nav.screenStack.lastIndex] = detail.copy(overridesCount = 0)
            } else {
                nav.replaceTop(copy(overrides = refreshed, selectedIndex = selectedIndex.coerceAtMost(refreshed.lastIndex)))
            }
        },
        onBack = { nav.pop() },
    )

    private fun colorListHandler() = scrollable<LauncherScreen.ColorList>(
        onConfirm = { colors.getOrNull(selectedIndex)?.let { launcherActions.openColorPicker(it.key) } },
    )

    private fun collectionPickerHandler() = scrollable<LauncherScreen.CollectionPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onCollectionPickerConfirm(this) },
        onWest = { nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = gamePaths) },
    )

    private fun childPickerHandler() = scrollable<LauncherScreen.ChildPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
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

    private fun shortcutBindingHandler() = scrollable<LauncherScreen.ShortcutBinding>(
        onMove = { idx -> if (listening) this else copy(selectedIndex = idx) },
        onConfirm = {
            if (!listening) {
                nav.replaceTop(copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                bindingController.startListening()
            }
        },
        onBack = {
            bindingController.stopListening()
            globalOverrides.saveShortcuts(shortcuts)
            nav.pop()
        },
        onNorth = {
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

    private fun rebuildForSource(
        state: LauncherScreen.PlatformDetail,
        newSource: dev.cannoli.scorza.config.EmulatorSource
    ): LauncherScreen.PlatformDetail {
        val bundled = LaunchManager.extractBundledCores(context)
        val options = platformConfig.emulatorOptionsForSource(
            tag = state.tag, source = newSource, includeAll = false,
            installedRaCores = installedCoreService.configuredCores(),
            embeddedCoresDir = bundled, pm = context.packageManager,
        )
        val savedRunner = platformConfig.getRunnerLabel(
            state.tag,
            platformConfig.getCoreMapping(state.tag),
            installedCoreService.configuredCores(),
        )
        val savedSource = dev.cannoli.scorza.config.EmulatorSource.fromRunnerLabel(savedRunner)
        val pending = if (newSource == savedSource) {
            options.firstOrNull { opt ->
                when (newSource) {
                    dev.cannoli.scorza.config.EmulatorSource.Standalone ->
                        opt.appPackage == platformConfig.getAppPackage(state.tag)
                    else -> opt.coreId == platformConfig.getCoreMapping(state.tag)
                }
            } ?: options.firstOrNull()
        } else {
            options.firstOrNull()
        }
        val previewLabel = pending?.displayName ?: context.getString(newSource.emptyMessageRes)
        val sourceLabel = if (newSource == dev.cannoli.scorza.config.EmulatorSource.RetroArch && pending != null)
            pending.runnerLabel
        else
            newSource.displayName
        return state.copy(
            currentSource = newSource,
            currentSourceLabel = sourceLabel,
            currentEmulatorLabel = previewLabel,
            emulatorRowPickable = options.size != 1,
            pendingPick = pending,
            dirty = true,
        )
    }

    private fun refreshEmulatorMappingOnStack() {
        val top = nav.screenStack.lastOrNull() as? LauncherScreen.EmulatorMapping ?: return
        val all = emulatorMappingBuilder.detailedMappings()
        val filtered = emulatorMappingBuilder.filter(all, top.filter)
        nav.screenStack[nav.screenStack.lastIndex] = top.copy(
            mappings = filtered,
            allMappings = all,
            selectedIndex = top.selectedIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
        )
    }
}
