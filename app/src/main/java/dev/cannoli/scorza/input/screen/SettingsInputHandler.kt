package dev.cannoli.scorza.input.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.PageJump
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.IntentAuditor
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.VirtualPlatformTags
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import dev.cannoli.scorza.ui.viewmodel.SettingsKey
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.KeyboardState
import dev.cannoli.scorza.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class SettingsInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val globalOverrides: GlobalOverridesManager,
    private val appsRepository: AppsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val inputTesterController: InputTesterController,
    private val updateManager: UpdateManager,
    private val intentAuditor: IntentAuditor,
    private val settingsViewModel: SettingsViewModel,
    private val dialogInputHandler: dev.cannoli.scorza.input.DialogInputHandler,
    private val launcherActions: LauncherActions,
    private val activityActions: ActivityActions,
    private val emulatorMappingBuilder: dev.cannoli.scorza.input.EmulatorMappingBuilder,
    @ApplicationContext private val context: Context,
    private val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
    private val cannoliPaths: CannoliPathsProvider,
    private val raLoginController: dev.cannoli.scorza.achievements.RaLoginController,
    private val permissionsInputHandler: PermissionsInputHandler,
    private val coreUpdateController: dev.cannoli.scorza.input.CoreUpdateController,
    private val shaderUpdateController: dev.cannoli.scorza.input.ShaderUpdateController,
) : ScreenInputHandler {

    override fun onUp() {
        settingsViewModel.moveSelection(-1)
    }

    override fun onDown() {
        settingsViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(-1, repeatCount = nav.lastKeyRepeatCount)
            if (settingsViewModel.getSelectedItem()?.key == SettingsKey.RELEASE_CHANNEL.id) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            pageJump(-1)
        }
    }

    // The shoulders travel, the D-pad places. Only meaningful on rows that take a coarse step;
    // elsewhere cycleSelected ignores it and the row behaves as it does under Left and Right.
    override fun onL1() {
        if (settingsViewModel.state.value.inSubList)
            settingsViewModel.cycleSelected(-1, repeatCount = nav.lastKeyRepeatCount, coarse = true)
    }

    override fun onR1() {
        if (settingsViewModel.state.value.inSubList)
            settingsViewModel.cycleSelected(1, repeatCount = nav.lastKeyRepeatCount, coarse = true)
    }

    override fun onRight() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(1, repeatCount = nav.lastKeyRepeatCount)
            if (settingsViewModel.getSelectedItem()?.key == SettingsKey.RELEASE_CHANNEL.id) {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            pageJump(1)
        }
    }

    override fun onConfirm() {
        if (!settingsViewModel.state.value.inSubList) {
            settingsViewModel.enterCategory()
            return
        }

        val key = settingsViewModel.enterSelected() ?: return
        when (SettingsKey.fromId(key)) {
            SettingsKey.INTEGRATIONS_RA -> {
                if (settings.raToken.isNotEmpty()) raLoginController.openAccountMenu()
                else settingsViewModel.enterSubCategory(SettingsCategory.RETROACHIEVEMENTS, dev.cannoli.scorza.R.string.settings_retroachievements)
            }
            SettingsKey.INTEGRATIONS_ROMM -> {
                if (rommStore.token.isNullOrEmpty()) settingsViewModel.enterSubCategory(SettingsCategory.ROMM, dev.cannoli.scorza.R.string.settings_romm)
                else nav.dialogState.value = DialogState.RommConnected(
                    host = rommStore.host, username = rommStore.username, version = rommStore.serverVersion)
            }
            SettingsKey.STATUS_BAR -> settingsViewModel.enterSubCategory(SettingsCategory.STATUS_BAR, dev.cannoli.scorza.R.string.settings_status_bar)
            SettingsKey.FGH_COLLECTION -> settingsViewModel.enterSubCategory(
                SettingsCategory.FGH_COLLECTION_PICKER,
                dev.cannoli.scorza.R.string.setting_fgh_collection,
                settingsViewModel.fghPickerInitialIndex()
            )
            SettingsKey.START_ON_PLATFORM -> settingsViewModel.enterSubCategory(
                SettingsCategory.START_ON_PICKER,
                dev.cannoli.ui.R.string.setting_start_on,
                settingsViewModel.startOnPickerInitialIndex()
            )
            SettingsKey.SD_ROOT -> pushDirectoryBrowser(BrowsePurpose.SD_ROOT, settings.sdCardRoot)
            SettingsKey.ROM_DIRECTORY -> {
                val startPath = settings.romDirectory.ifEmpty { settings.sdCardRoot }
                pushDirectoryBrowser(BrowsePurpose.ROM_DIRECTORY, startPath)
            }
            SettingsKey.COLORS -> nav.push(LauncherScreen.ColorList(colors = settingsViewModel.getColorEntries()))
            SettingsKey.CONTROLLERS -> nav.push(LauncherScreen.Controllers())
            SettingsKey.SCREEN_GEOMETRY -> settingsViewModel.enterSubCategory(SettingsCategory.SCREEN_GEOMETRY, dev.cannoli.scorza.R.string.setting_screen_geometry)
            SettingsKey.LOGGING -> nav.push(LauncherScreen.LoggingSettings())
            SettingsKey.PERMISSIONS -> permissionsInputHandler.open()
            SettingsKey.AUDIT_EMULATOR_INTENTS -> runIntentAudit()
            SettingsKey.WIFI_DIRECT_PROBE -> runWifiDirectProbe(host = false)
            SettingsKey.WIFI_DIRECT_HOST -> runWifiDirectProbe(host = true)
            SettingsKey.ICON_GALLERY -> nav.push(LauncherScreen.IconGallery())
            SettingsKey.SHORTCUTS -> nav.push(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
            SettingsKey.INPUT_TESTER -> {
                inputTesterController.enter()
                nav.push(LauncherScreen.InputTester)
            }
            SettingsKey.CORE_MAPPING -> openEmulatorMapping()
            SettingsKey.SET_DEFAULT_LAUNCHER -> context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            SettingsKey.UPDATE_CORES -> coreUpdateController.confirm()
            SettingsKey.UPDATE_SHADERS -> shaderUpdateController.confirm()
            SettingsKey.INSTALLED_CORES -> nav.push(emulatorMappingBuilder.buildInstalledCores())
            SettingsKey.MANAGE_TOOLS -> openAppPicker(VirtualPlatformTags.TOOLS)
            SettingsKey.MANAGE_PORTS -> openAppPicker(VirtualPlatformTags.PORTS)
            SettingsKey.RESET_CUSTOM_CONFIG -> {
                nav.dialogState.value = DialogState.ResetCustomConfigConfirm
            }
            SettingsKey.REGENERATE_SYSTEM_FOLDERS -> {
                val romDir = cannoliPaths.romDir
                val tags = platformConfig.getAllTags()
                ioScope.launch {
                    val created = dev.cannoli.scorza.util.DirectoryLayout.scaffoldRomFolders(romDir, tags)
                    val msg = if (created == 0) {
                        context.getString(dev.cannoli.scorza.R.string.regenerate_system_folders_none)
                    } else {
                        context.resources.getQuantityString(
                            dev.cannoli.scorza.R.plurals.regenerate_system_folders_result, created, created
                        )
                    }
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.SystemFoldersRegenerated(msg)
                    }
                }
            }
            SettingsKey.TITLE -> {
                val current = settings.title
                nav.dialogState.value = DialogState.RenameInput(
                    target = RenameTarget.LauncherTitle,
                    keyboard = KeyboardState(text = current, cursorPos = current.length)
                )
            }
            SettingsKey.RA_USERNAME -> {
                val current = settings.raUsername
                nav.dialogState.value = DialogState.RenameInput(
                    target = RenameTarget.RaUsername,
                    keyboard = KeyboardState(text = current, cursorPos = current.length)
                )
            }
            SettingsKey.RA_PASSWORD -> {
                nav.dialogState.value = DialogState.RenameInput(
                    target = RenameTarget.RaPassword,
                    keyboard = KeyboardState(text = settingsViewModel.raPassword, cursorPos = settingsViewModel.raPassword.length)
                )
            }
            SettingsKey.RA_LOGIN -> activityActions.startRaLogin(settings.raUsername, settingsViewModel.raPassword)
            SettingsKey.ROMM_HOST -> {
                val current = rommStore.host
                nav.dialogState.value = DialogState.RenameInput(target = RenameTarget.RommHost, keyboard = KeyboardState(text = current, cursorPos = current.length))
            }
            SettingsKey.ROMM_PAIR -> activityActions.startRommPairing(rommStore.host)
            SettingsKey.ROMM_PAIR_CODE -> {
                nav.dialogState.value = DialogState.RenameInput(target = RenameTarget.RommPairCode, keyboard = KeyboardState())
            }
            SettingsKey.COLOR_BACKGROUND, SettingsKey.COLOR_TEXT, SettingsKey.COLOR_STATUS_BAR,
            SettingsKey.COLOR_HIGHLIGHT, SettingsKey.COLOR_HIGHLIGHT_TEXT, SettingsKey.COLOR_ACCENT,
            SettingsKey.COLOR_TITLE -> {
                val entries = settingsViewModel.getColorEntries()
                val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                nav.push(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                launcherActions.openColorPicker(key)
            }
            // A pick carries its target in the key, so it never resolves to a constant.
            null -> if (key.startsWith(SettingsKey.FGH_PICK_PREFIX)) {
                val id = key.removePrefix(SettingsKey.FGH_PICK_PREFIX).toLongOrNull()
                settingsViewModel.selectFghCollectionId(id)
                settingsViewModel.save()
                settingsViewModel.exitSubList()
                launcherActions.rescanSystemList()
            } else if (key.startsWith(SettingsKey.START_ON_PICK_PREFIX)) {
                // An empty tag is the System List row, which is the prefix on its own.
                settingsViewModel.selectStartOnPlatform(key.removePrefix(SettingsKey.START_ON_PICK_PREFIX))
                settingsViewModel.save()
                settingsViewModel.exitSubList()
            }
            // Rows that cycle a value rather than open something: Confirm does nothing.
            SettingsKey.LANGUAGE, SettingsKey.SWAP_PLAY_RESUME, SettingsKey.MAIN_MENU_QUIT,
            SettingsKey.BG_IMAGE, SettingsKey.BG_TINT, SettingsKey.FONT, SettingsKey.TEXT_SIZE,
            SettingsKey.ART_SCALE, SettingsKey.ART_WIDTH, SettingsKey.PORTRAIT_MARGIN,
            SettingsKey.CONTENT_MODE, SettingsKey.SHOW_RECENTLY_PLAYED, SettingsKey.SHOW_FAVORITES,
            SettingsKey.SCAN_LIBRARY, SettingsKey.SHOW_BATTERY, SettingsKey.SHOW_BLUETOOTH,
            SettingsKey.SHOW_CLOCK, SettingsKey.SHOW_KITCHEN, SettingsKey.SHOW_DOWNLOADS,
            SettingsKey.SHOW_UPDATE, SettingsKey.SHOW_VPN, SettingsKey.SHOW_WIFI,
            SettingsKey.SCREEN_GEO_WIDTH, SettingsKey.SCREEN_GEO_HEIGHT, SettingsKey.SCREEN_GEO_X,
            SettingsKey.SCREEN_GEO_Y, SettingsKey.ALWAYS_SAVE_ON_QUIT, SettingsKey.IGM_SETTINGS_MODE,
            SettingsKey.DEFAULT_VIDEO_DRIVER, SettingsKey.ROMM_ALLOW_SELF_SIGNED,
            SettingsKey.KITCHEN_CODE_BYPASS,
            SettingsKey.RELEASE_CHANNEL -> {}
        }
    }

    override fun onBack() {
        val state = settingsViewModel.state.value
        val screen = nav.currentScreen as? LauncherScreen.Settings
        // The quick menu is only returned to from the level it dropped us at; anything deeper
        // still unwinds one level at a time.
        val quickMenuRow = screen?.quickMenuRow?.takeIf {
            state.parentCategory == null && state.activeCategory == screen.quickMenuCategory
        }
        if (state.inSubList) {
            settingsViewModel.save()
            launcherActions.rescanSystemList()
        }
        if (quickMenuRow == null) {
            if (state.inSubList) {
                settingsViewModel.exitSubList()
            } else {
                settingsViewModel.cancel()
                nav.pop()
            }
            return
        }
        // Held until the rebuilt menu can replace the screen in one frame; unwinding first would
        // flash whatever is underneath while the menu is still being built.
        dialogInputHandler.openQuickMenu(quickMenuRow) {
            if (state.inSubList) settingsViewModel.exitSubList() else settingsViewModel.cancel()
            nav.pop()
        }
    }

    override fun onNorth() {
        val item = settingsViewModel.getSelectedItem()
        if (item?.key == SettingsKey.ROM_DIRECTORY.id && settings.romDirectory.isNotEmpty()) {
            launcherActions.confirmRomDirectoryChange("")
            return
        }
        if (settingsViewModel.state.value.activeCategory == SettingsCategory.SCREEN_GEOMETRY) {
            settingsViewModel.resetScreenGeometry()
        }
    }

    private fun pageJump(direction: Int) {
        val state = settingsViewModel.state.value
        val newIdx = PageJump.compute(direction, state.categories.size, state.categoryIndex, nav.activeListState)
        if (newIdx != state.categoryIndex) settingsViewModel.setCategoryIndex(newIdx)
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        nav.push(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun openEmulatorMapping() {
        // Built once: the rows come from a directory read, so there is no scan to wait for.
        val initial = emulatorMappingBuilder.detailedMappings()
        nav.push(LauncherScreen.EmulatorMapping(mappings = initial, allMappings = initial))
    }

    private fun runIntentAudit() {
        ioScope.launch {
            val message = try {
                val result = intentAuditor.runAudit()
                "Audited ${result.totalInstalled} installed emulators; ${result.totalFailed} intents failed to resolve.\n\nReport: ${result.reportFile.absolutePath}"
            } catch (e: Exception) {
                "Audit failed: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                nav.dialogState.value = DialogState.IntentAuditResult(message)
            }
        }
    }

    // Throwaway, with the Wi-Fi Direct netplay spike. Remove with it.
    private var wifiDirectProbe: dev.cannoli.scorza.netplay.WifiDirectProbe? = null

    /**
     * Shows what the other handhelds nearby are advertising, live.
     *
     * A Picker rather than a screen of its own: the join list this is standing in for is a list of
     * rows and what to do with the chosen one, which is what a Picker is. Rebuilt on every change
     * the way the toggles are, so a handheld appearing mid-discovery shows up without a refresh.
     */
    private fun runWifiDirectProbe(host: Boolean) {
        wifiDirectProbe?.stop()
        val probe = dev.cannoli.scorza.netplay.WifiDirectProbe(context)
        wifiDirectProbe = probe
        probe.onPeers = { peers -> showWifiDirectPeers(peers) }
        probe.onLink = { state, numbers -> showWifiDirectLink(state, numbers) }
        try {
            probe.run(settings.sdCardRoot, asHost = host)
        } catch (e: Exception) {
            nav.dialogState.value = DialogState.IntentAuditResult(
                "Probe failed: ${e.message ?: e.javaClass.simpleName}"
            )
            return
        }
        showWifiDirectPeers(emptyList())
    }

    private fun showWifiDirectPeers(peers: List<dev.cannoli.scorza.netplay.WifiDirectGroup.Peer>) {
        val current = nav.dialogState.value
        // Only while this screen is the one on top: discovery keeps answering after you leave, and
        // pushing a peer list over whatever replaced it would be worse than missing one.
        if (peers.isNotEmpty() && current !is DialogState.Picker) return
        nav.dialogState.value = DialogState.Picker(
            title = "Wi-Fi Direct Test",
            confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
            emptyMessage = "Searching for nearby handhelds...",
            items = peers.map {
                dev.cannoli.scorza.ui.screens.PickerItem(
                    label = it.deviceName,
                    value = it.record["game"].orEmpty().ifEmpty { it.record["crc"].orEmpty() },
                )
            },
            onBack = {
                wifiDirectProbe?.stop()
                wifiDirectProbe = null
                nav.dialogState.value = DialogState.None
            },
        ) { index ->
            peers.getOrNull(index)?.let { wifiDirectProbe?.connectTo(it) }
        }
    }

    /**
     * The numbers crossing the group, newest first.
     *
     * Both handhelds show the same list, one because it made them and one because it received
     * them, which is the end of the question: discovery, a group, and a TCP path that stays up.
     */
    private fun showWifiDirectLink(state: String, numbers: List<Int>) {
        if (wifiDirectProbe == null) return
        nav.dialogState.value = DialogState.Picker(
            title = "Wi-Fi Direct Test - $state",
            confirmLabel = context.getString(dev.cannoli.scorza.R.string.label_select),
            emptyMessage = "Waiting for the link...",
            items = numbers.map { dev.cannoli.scorza.ui.screens.PickerItem(it.toString()) },
            onBack = {
                wifiDirectProbe?.stop()
                wifiDirectProbe = null
                nav.dialogState.value = DialogState.None
            },
        ) { }
    }

    private fun openAppPicker(type: String) {
        val installed = getInstalledLauncherApps()
        val allApps = buildList {
            if (type == VirtualPlatformTags.TOOLS && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                add(context.getString(dev.cannoli.ui.R.string.app_android_tv_settings) to ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE)
            }
            addAll(installed)
        }
        val appType = if (type == VirtualPlatformTags.TOOLS) AppType.TOOL else AppType.PORT
        val existing = appsRepository.all(appType).map { it.packageName }.toSet()
        val initialChecked = allApps.indices.filter { allApps[it].second in existing }.toSet()
        val title = context.getString(
            if (type == VirtualPlatformTags.TOOLS) dev.cannoli.ui.R.string.title_manage_tools
            else dev.cannoli.ui.R.string.title_manage_ports
        )
        nav.push(LauncherScreen.AppPicker(
            type = type,
            title = title,
            apps = allApps.map { it.first },
            packages = allApps.map { it.second },
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun getInstalledLauncherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(context.packageManager).toString()
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
    }

    fun confirmAppPicker(state: LauncherScreen.AppPicker) {
        val selected = state.checkedIndices.mapNotNull { idx ->
            val name = state.apps.getOrNull(idx) ?: return@mapNotNull null
            val pkg = state.packages.getOrNull(idx) ?: return@mapNotNull null
            name to pkg
        }
        val appType = if (state.type == VirtualPlatformTags.TOOLS) AppType.TOOL else AppType.PORT
        ioScope.launch {
            val keep = selected.map { it.second }.toSet()
            appsRepository.all(appType).forEach { app ->
                if (app.packageName !in keep) appsRepository.delete(app.id)
            }
            selected.forEach { (name, pkg) -> appsRepository.upsert(appType, name, pkg) }
            launcherActions.rescanSystemList()
        }
        nav.pop()
    }
}
