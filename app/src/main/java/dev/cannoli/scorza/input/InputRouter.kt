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
import dev.cannoli.scorza.input.screen.SaveSlotsInputHandler
import dev.cannoli.scorza.input.screen.SaveStatePickerInputHandler
import dev.cannoli.scorza.input.screen.SystemListInputHandler
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.input.runtime.InputDispatcher
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val saveSlotsHandler: SaveSlotsInputHandler,
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
    private val settings: SettingsRepository,
    private val coreInstaller: CoreInstaller,
    private val rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel,
    private val rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader,
    private val osdController: dev.cannoli.ui.components.OsdController,
    private val raPreloadController: dev.cannoli.scorza.ra.RaPreloadController,
    @IoScope private val ioScope: CoroutineScope,
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
        is LauncherScreen.SaveSlots -> saveSlotsHandler
        is LauncherScreen.Controllers -> controllersHandler
        is LauncherScreen.ControllerDetail -> controllerDetailHandler
        is LauncherScreen.EditButtons -> editButtonsHandler
        is LauncherScreen.LoggingSettings -> loggingSettingsHandler
        is LauncherScreen.OnboardingPermissions -> onboardingHandler
        is LauncherScreen.DirectoryBrowser -> onboardingHandler
        is LauncherScreen.RommGameDetail -> object : ScreenInputHandler {
            override fun onWest() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                if (s.game.ssMedia?.manual == null) return
                rommDownloader.enqueue(listOf(dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = s.game.id, game = s.game, tag = s.tag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.MANUAL)))
                dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                osdController.show(context.getString(dev.cannoli.ui.R.string.romm_osd_manual_queued))
            }
            override fun onBack() { nav.pop() }
            override fun onUp() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                nav.replaceTop(s.copy(scrollStep = (s.scrollStep - 1).coerceAtLeast(0)))
            }
            override fun onDown() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                nav.replaceTop(s.copy(scrollStep = s.scrollStep + 1))
            }
            override fun onNorth() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                if (s.localState != dev.cannoli.scorza.romm.LocalState.REMOTE) return
                rommDownloader.enqueue(listOf(dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = s.game.id, game = s.game, tag = s.tag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.ROM)))
                dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                osdController.show(context.getString(dev.cannoli.ui.R.string.romm_osd_download_queued))
            }
        }
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
        is LauncherScreen.PlatformMapping     -> platformMappingHandler()
        is LauncherScreen.BiosStatus          -> biosStatusHandler()
        is LauncherScreen.PlatformOverrides   -> platformOverridesHandler()
        is LauncherScreen.ColorList         -> colorListHandler()
        is LauncherScreen.CollectionPicker  -> collectionPickerHandler()
        is LauncherScreen.ChildPicker       -> childPickerHandler()
        is LauncherScreen.AppPicker         -> appPickerHandler()
        is LauncherScreen.ShortcutBinding   -> shortcutBindingHandler()
        is LauncherScreen.Credits           -> creditsHandler()
        is LauncherScreen.InstalledCores    -> installedCoresHandler()
        is LauncherScreen.RommPlatformList      -> rommPlatformListHandler()
        is LauncherScreen.RommGameList          -> rommGameListHandler()
        is LauncherScreen.RommGlobalSearch      -> rommGlobalSearchHandler()
        is LauncherScreen.RommFirmwareList      -> rommFirmwareListHandler()
        is LauncherScreen.RommCollectionGroups  -> rommCollectionGroupsHandler()
        is LauncherScreen.RommVirtualTypes      -> rommVirtualTypesHandler()
        is LauncherScreen.RommCollectionList    -> rommCollectionListHandler()
        is LauncherScreen.RommCollectionGameList -> rommCollectionGameListHandler()
        is LauncherScreen.RetroAchievementsOfflinePlatforms -> raOfflinePlatformsHandler()
        is LauncherScreen.RetroAchievementsOfflineSets -> raOfflineSetsHandler()
        else -> object : ScreenInputHandler {}
    }

    private fun raOfflineStore() = dev.cannoli.scorza.ra.RaOfflineStore(
        dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).configRaOffline
    )

    private fun reloadOfflineSets() {
        val s = nav.currentScreen as? LauncherScreen.RetroAchievementsOfflineSets ?: return
        val entries = raOfflineStore().entries().filter { it.platformTag == s.platformTag }
        nav.replaceTop(s.copy(entries = entries, selectedIndex = s.selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))))
    }

    private fun refreshOfflinePlatforms() {
        val s = nav.currentScreen as? LauncherScreen.RetroAchievementsOfflinePlatforms ?: return
        val platforms = raOfflineStore().entries()
            .groupBy { it.platformTag }
            .map { (tag, list) -> LauncherScreen.RaOfflinePlatform(tag, platformConfig.getDisplayName(tag), list.size) }
            .sortedBy { it.name.lowercase() }
        nav.replaceTop(s.copy(platforms = platforms, selectedIndex = s.selectedIndex.coerceIn(0, (platforms.size - 1).coerceAtLeast(0))))
    }

    private fun raOfflinePlatformsHandler() = scrollable<LauncherScreen.RetroAchievementsOfflinePlatforms>(
        onBack = { nav.pop() },
        onConfirm = {
            val p = platforms.getOrNull(selectedIndex) ?: return@scrollable
            val entries = raOfflineStore().entries().filter { it.platformTag == p.tag }
            nav.push(
                LauncherScreen.RetroAchievementsOfflineSets(
                    platformTag = p.tag,
                    platformName = p.name,
                    entries = entries,
                )
            )
        },
    )

    private fun raOfflineSetsHandler() = scrollable<LauncherScreen.RetroAchievementsOfflineSets>(
        onBack = {
            nav.pop()
            refreshOfflinePlatforms()
        },
        onNorth = {
            val entry = entries.getOrNull(selectedIndex) ?: return@scrollable
            raPreloadController.start(
                romPath = entry.romPath,
                platformTag = entry.platformTag,
                displayName = entry.gameName,
                gameId = entry.gameId,
                onComplete = { reloadOfflineSets() },
            )
        },
        onWest = {
            val entry = entries.getOrNull(selectedIndex) ?: return@scrollable
            raOfflineStore().deleteGame(entry.gameId)
            reloadOfflineSets()
        },
    )

    private inline fun <reified T> scrollable(
        crossinline onConfirm: T.() -> Unit = {},
        crossinline onBack: T.() -> Unit = { nav.pop() },
        crossinline onMove: T.(Int) -> LauncherScreen = { idx -> withScroll(selectedIndex = idx, scrollTarget = scrollTarget) },
        noinline onStart: (T.() -> Unit)? = null,
        noinline onWest: (T.() -> Unit)? = null,
        noinline onNorth: (T.() -> Unit)? = null,
        noinline onLeft: (T.() -> Unit)? = null,
        noinline onRight: (T.() -> Unit)? = null,
        noinline onSelect: (T.() -> Unit)? = null,
        noinline onR1: (T.() -> Unit)? = null,
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
            onSelect = onSelect?.let { fn -> { current()?.fn() ?: Unit } },
            onR1 = onR1?.let { fn -> { current()?.fn() ?: Unit } },
        )
    }

    private fun emulatorMappingHandler() = scrollable<LauncherScreen.EmulatorMapping>(
        onConfirm = {
            val entry = mappings.getOrNull(selectedIndex) ?: return@scrollable
            nav.push(emulatorMappingBuilder.buildPlatformMapping(entry.tag, entry.platformName, showAll = false, defaultShowAllIfEmpty = true))
        },
        onBack = { nav.pop() },
        onWest = {
            // Skip filter buckets that are currently empty; All (0) is the always-available fallback.
            var newFilter = (filter + 1) % 4
            while (newFilter != 0 && emulatorMappingBuilder.filter(allMappings, newFilter).isEmpty()) {
                newFilter = (newFilter + 1) % 4
            }
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

    private fun platformMappingHandler() = scrollable<LauncherScreen.PlatformMapping>(
        onConfirm = {
            val item = selectableItems.getOrNull(selectedIndex) ?: return@scrollable
            when (item) {
                is dev.cannoli.scorza.ui.screens.MappingItem.EmulatorOption -> {
                    val opt = item.option
                    if (item.downloadable) {
                        downloadCoreThenAssign(tag, platformName, opt.coreId, showAll, selectedIndex, scrollTarget)
                    } else {
                        if (opt.appPackage != null) {
                            platformConfig.setAppMapping(tag, opt.appPackage)
                        } else {
                            platformConfig.setCoreMapping(tag, opt.coreId, opt.runnerLabel)
                        }
                        platformConfig.saveCoreMappings()
                        nav.replaceTop(emulatorMappingBuilder.buildPlatformMapping(
                            tag, platformName, showAll = showAll,
                            selectedIndex = selectedIndex, scrollTarget = scrollTarget,
                        ))
                        refreshEmulatorMappingOnStack()
                    }
                }
                is dev.cannoli.scorza.ui.screens.MappingItem.Action -> when (item.kind) {
                    dev.cannoli.scorza.ui.screens.MappingActionKind.BIOS -> {
                        nav.push(emulatorMappingBuilder.buildBiosStatus(tag, platformName))
                    }
                    dev.cannoli.scorza.ui.screens.MappingActionKind.OVERRIDES -> {
                        val list = platformConfig.getPlatformOverrides(tag)
                        nav.push(LauncherScreen.PlatformOverrides(
                            tag = tag, platformName = platformName, overrides = list,
                        ))
                    }
                    dev.cannoli.scorza.ui.screens.MappingActionKind.RESET -> {
                        nav.dialogState.value = DialogState.PlatformResetConfirm(tag = tag, platformName = platformName)
                    }
                }
                else -> Unit
            }
        },
        onBack = { nav.pop() },
        onNorth = {
            nav.replaceTop(emulatorMappingBuilder.buildPlatformMapping(
                tag, platformName, showAll = !showAll, selectedIndex = 0, scrollTarget = 0,
            ))
        },
    )

    private fun biosStatusHandler() = scrollable<LauncherScreen.BiosStatus>(
        onBack = { nav.pop() },
    )

    private fun platformOverridesHandler() = scrollable<LauncherScreen.PlatformOverrides>(
        onNorth = {
            val entry = overrides.getOrNull(selectedIndex)
            if (entry != null) {
                platformConfig.clearGameOverride(entry.first)
                val refreshed = platformConfig.getPlatformOverrides(tag)
                if (refreshed.isEmpty()) {
                    nav.pop()
                    val mapping = nav.screenStack.lastOrNull() as? LauncherScreen.PlatformMapping
                    if (mapping != null) {
                        nav.screenStack[nav.screenStack.lastIndex] = emulatorMappingBuilder.buildPlatformMapping(
                            mapping.tag, mapping.platformName, showAll = mapping.showAll,
                            selectedIndex = mapping.selectedIndex, scrollTarget = mapping.scrollTarget,
                        )
                    }
                } else {
                    nav.replaceTop(copy(overrides = refreshed, selectedIndex = selectedIndex.coerceAtMost(refreshed.lastIndex)))
                }
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

    private fun rommPlatformListHandler() = scrollable<LauncherScreen.RommPlatformList>(
        onConfirm = {
            val showCollectionsRow = rommBrowseViewModel.hasAnyCollections()
            if (showCollectionsRow && selectedIndex == 0) {
                when (val target = rommBrowseViewModel.collectionEntryTarget()) {
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.Landing ->
                        nav.push(LauncherScreen.RommCollectionGroups())
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.VirtualTypes ->
                        nav.push(LauncherScreen.RommVirtualTypes())
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.Group ->
                        nav.push(LauncherScreen.RommCollectionList(group = target.group))
                }
            } else {
                val offset = if (showCollectionsRow) 1 else 0
                val platform = rommBrowseViewModel.platforms.value.getOrNull(selectedIndex - offset) ?: return@scrollable
                ioScope.launch {
                    rommBrowseViewModel.openPlatform(platform)
                    withContext(Dispatchers.Main) { nav.push(LauncherScreen.RommGameList(platform = platform)) }
                }
            }
        },
        onBack = {},
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_global_search",
            )
        },
    )

    private fun rommCollectionGroupsHandler() = scrollable<LauncherScreen.RommCollectionGroups>(
        onConfirm = {
            val order = listOf(
                dev.cannoli.scorza.romm.RommCollectionGroup.USER,
                dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL,
                dev.cannoli.scorza.romm.RommCollectionGroup.SMART,
            )
            val enabled = order.filter { it in rommBrowseViewModel.enabledGroups() }
            val group = enabled.getOrNull(selectedIndex) ?: return@scrollable
            if (group == dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL) nav.push(LauncherScreen.RommVirtualTypes())
            else nav.push(LauncherScreen.RommCollectionList(group = group))
        },
        onBack = { nav.pop() },
    )

    private fun rommVirtualTypesHandler() = scrollable<LauncherScreen.RommVirtualTypes>(
        onConfirm = {
            val type = rommBrowseViewModel.virtualTypeCounts.value.getOrNull(selectedIndex)?.first ?: return@scrollable
            nav.push(LauncherScreen.RommCollectionList(group = dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL, virtualType = type))
        },
        onBack = { nav.pop() },
    )

    private fun rommCollectionListHandler() = scrollable<LauncherScreen.RommCollectionList>(
        onConfirm = {
            val key = group.name + (virtualType?.let { ":$it" } ?: "")
            val loaded = rommBrowseViewModel.collectionList.value
            if (loaded?.id != key) return@scrollable
            val collection = loaded.rows.getOrNull(selectedIndex) ?: return@scrollable
            ioScope.launch {
                rommBrowseViewModel.openCollection(collection)
                withContext(Dispatchers.Main) { nav.push(LauncherScreen.RommCollectionGameList(collection = collection)) }
            }
        },
        onBack = { nav.pop() },
    )

    private fun rommCollectionGameListHandler() = scrollable<LauncherScreen.RommCollectionGameList>(
        onConfirm = {
            val row = rommBrowseViewModel.collectionGames.value?.rows?.getOrNull(selectedIndex) ?: return@scrollable
            nav.push(LauncherScreen.RommGameDetail(
                game = row.game,
                localState = row.localState,
                platformName = row.platform.displayName,
                tag = row.platform.cannoliTag,
            ))
        },
        onBack = {
            if (search.isNotEmpty()) nav.replaceTop(copy(search = "", selectedIndex = 0, scrollTarget = 0))
            else nav.pop()
        },
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_collection_search",
                searchScope = collection.name,
                keyboard = KeyboardState(text = search, cursorPos = search.length),
            )
        },
    )

    private fun rommGameListHandler() = scrollable<LauncherScreen.RommGameList>(
        onConfirm = {
            val row = rommBrowseViewModel.games.value?.rows?.getOrNull(selectedIndex) ?: return@scrollable
            if (rommBrowseViewModel.isMultiSelect()) {
                rommBrowseViewModel.toggleChecked(row.game.id)
            } else {
                nav.push(LauncherScreen.RommGameDetail(
                    game = row.game,
                    localState = row.localState,
                    platformName = platform.displayName,
                    tag = platform.cannoliTag,
                ))
            }
        },
        onBack = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else if (search.isNotEmpty()) nav.replaceTop(copy(search = "", selectedIndex = 0, scrollTarget = 0))
            else nav.pop()
        },
        onStart = {
            if (!rommBrowseViewModel.isMultiSelect()) return@scrollable
            val games = rommBrowseViewModel.confirmMultiSelect()
            if (games.isEmpty()) return@scrollable
            rommDownloader.enqueue(games.map {
                dev.cannoli.scorza.romm.download.RommDownloadItem(rommId = it.id, game = it, tag = platform.cannoliTag)
            })
            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
            osdController.show(context.resources.getQuantityString(
                dev.cannoli.ui.R.plurals.romm_osd_downloads_queued, games.size, games.size))
        },
        onWest = { if (platform.firmwareCount > 0) nav.push(LauncherScreen.RommFirmwareList(platform = platform)) },
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_search",
                searchScope = platform.displayName,
                keyboard = KeyboardState(text = search, cursorPos = search.length),
            )
        },
        onSelect = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else rommBrowseViewModel.enterMultiSelect(
                rommBrowseViewModel.games.value?.rows?.getOrNull(selectedIndex)?.game?.id)
        },
    )

    private fun rommGlobalSearchHandler() = scrollable<LauncherScreen.RommGlobalSearch>(
        onConfirm = {
            val row = rommBrowseViewModel.searchResults.value.getOrNull(selectedIndex) ?: return@scrollable
            val platform = rommBrowseViewModel.allPlatforms.value.firstOrNull { it.id == row.game.platformId }
            nav.push(LauncherScreen.RommGameDetail(
                game = row.game,
                localState = row.localState,
                platformName = platform?.displayName ?: "",
                tag = platform?.cannoliTag ?: "",
            ))
        },
    )

    private fun rommFirmwareListHandler() = scrollable<LauncherScreen.RommFirmwareList>(
        onConfirm = {
            val row = rows.getOrNull(selectedIndex) ?: return@scrollable
            val id = row.firmware.id
            nav.replaceTop(copy(checkedIds = if (id in checkedIds) checkedIds - id else checkedIds + id))
        },
        onBack = { nav.pop() },
        onStart = {
            val chosen = rows.filter { it.firmware.id in checkedIds }
            if (chosen.isEmpty()) return@scrollable
            rommDownloader.enqueue(chosen.map {
                dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = it.firmware.id,
                    tag = platform.cannoliTag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.FIRMWARE,
                    firmware = it.firmware,
                )
            })
            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
            nav.pop()
        },
    )

    private fun downloadCoreThenAssign(
        tag: String,
        platformName: String,
        coreId: String,
        showAll: Boolean,
        selectedIndex: Int,
        scrollTarget: Int,
    ) {
        val pkg = settings.retroArchPackage
        val label = InstalledCoreService.getPackageLabel(pkg)
        val coreName = platformConfig.getCoreDisplayName(coreId)
        coreInstaller.downloadCore(pkg, coreId, coreName) {
            platformConfig.setCoreMapping(tag, coreId, label)
            platformConfig.saveCoreMappings()
            val top = nav.currentScreen as? LauncherScreen.PlatformMapping
            if (top != null && top.tag == tag) {
                nav.replaceTop(emulatorMappingBuilder.buildPlatformMapping(
                    tag, platformName, showAll = showAll,
                    selectedIndex = selectedIndex, scrollTarget = scrollTarget,
                ))
            }
            refreshEmulatorMappingOnStack()
        }
    }

    private fun refreshEmulatorMappingOnStack() {
        val idx = nav.screenStack.indexOfLast { it is LauncherScreen.EmulatorMapping }
        if (idx < 0) return
        val em = nav.screenStack[idx] as LauncherScreen.EmulatorMapping
        val all = emulatorMappingBuilder.detailedMappings()
        val filtered = emulatorMappingBuilder.filter(all, em.filter)
        nav.screenStack[idx] = em.copy(
            mappings = filtered,
            allMappings = all,
            selectedIndex = em.selectedIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
        )
    }
}
