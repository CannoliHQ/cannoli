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
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.IntentAuditor
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
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
    private val installedCoreService: InstalledCoreService,
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
            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
                ioScope.launch { updateManager.checkForUpdate() }
            }
        } else {
            pageJump(-1)
        }
    }

    override fun onRight() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(1, repeatCount = nav.lastKeyRepeatCount)
            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
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

        when (val key = settingsViewModel.enterSelected()) {
            "integrations_ra" -> {
                if (settings.raToken.isNotEmpty()) raLoginController.openAccountMenu()
                else settingsViewModel.enterSubCategory("retroachievements", dev.cannoli.scorza.R.string.settings_retroachievements)
            }
            "integrations_romm" -> {
                if (rommStore.token.isNullOrEmpty()) settingsViewModel.enterSubCategory("romm", dev.cannoli.scorza.R.string.settings_romm)
                else nav.dialogState.value = DialogState.RommConnected(
                    host = rommStore.host, username = rommStore.username, version = rommStore.serverVersion)
            }
            "status_bar" -> settingsViewModel.enterSubCategory("status_bar", dev.cannoli.scorza.R.string.settings_status_bar)
            "fgh_collection" -> settingsViewModel.enterSubCategory(
                "fgh_collection_picker",
                dev.cannoli.scorza.R.string.setting_fgh_collection,
                settingsViewModel.fghPickerInitialIndex()
            )
            "sd_root" -> pushDirectoryBrowser(BrowsePurpose.SD_ROOT, settings.sdCardRoot)
            "rom_directory" -> {
                val startPath = settings.romDirectory.ifEmpty { settings.sdCardRoot }
                pushDirectoryBrowser(BrowsePurpose.ROM_DIRECTORY, startPath)
            }
            "colors" -> nav.push(LauncherScreen.ColorList(colors = settingsViewModel.getColorEntries()))
            "controllers" -> nav.push(LauncherScreen.Controllers())
            "screen_geometry" -> settingsViewModel.enterSubCategory("screen_geometry", dev.cannoli.scorza.R.string.setting_screen_geometry)
            "logging" -> nav.push(LauncherScreen.LoggingSettings())
            "audit_emulator_intents" -> runIntentAudit()
            "icon_gallery" -> nav.push(LauncherScreen.IconGallery())
            "shortcuts" -> nav.push(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
            "input_tester" -> {
                inputTesterController.enter()
                nav.push(LauncherScreen.InputTester)
            }
            "core_mapping" -> openEmulatorMapping()
            "set_default_launcher" -> context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "installed_cores" -> queryInstalledCores()
            "manage_tools" -> openAppPicker("tools")
            "manage_ports" -> openAppPicker("ports")
            "reset_custom_config" -> {
                nav.dialogState.value = DialogState.ResetCustomConfigConfirm
            }
            "regenerate_system_folders" -> {
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
            "ra_username" -> {
                val current = settings.raUsername
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_username",
                    keyboard = KeyboardState(text = current, cursorPos = current.length)
                )
            }
            "ra_password" -> {
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_password",
                    keyboard = KeyboardState(text = settingsViewModel.raPassword, cursorPos = settingsViewModel.raPassword.length)
                )
            }
            "ra_login" -> activityActions.startRaLogin(settings.raUsername, settingsViewModel.raPassword)
            "romm_host" -> {
                val current = rommStore.host
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_host", keyboard = KeyboardState(text = current, cursorPos = current.length))
            }
            "romm_pair" -> activityActions.startRommPairing(rommStore.host)
            "romm_pair_code" -> {
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_pair_code", keyboard = KeyboardState())
            }
            null -> {}
            else -> {
                when {
                    key.startsWith("fgh_pick:") -> {
                        val id = key.removePrefix("fgh_pick:").toLongOrNull()
                        settingsViewModel.selectFghCollectionId(id)
                        settingsViewModel.save()
                        settingsViewModel.exitSubList()
                        launcherActions.rescanSystemList()
                    }
                    key.startsWith("color_") -> {
                        val entries = settingsViewModel.getColorEntries()
                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                        nav.push(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                        launcherActions.openColorPicker(key)
                    }
                    else -> {
                        val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = key,
                            keyboard = KeyboardState(text = displayValue, cursorPos = displayValue.length)
                        )
                    }
                }
            }
        }
    }

    override fun onBack() {
        val state = settingsViewModel.state.value
        if (state.inSubList) {
            // Only the category the quick menu pushed us into returns there; anything deeper
            // still unwinds one level at a time.
            val quickMenuRow = (nav.currentScreen as? LauncherScreen.Settings)
                ?.quickMenuRow
                ?.takeIf { state.parentCategory == null }
            settingsViewModel.save()
            settingsViewModel.exitSubList()
            launcherActions.rescanSystemList()
            if (quickMenuRow != null) {
                nav.pop()
                dialogInputHandler.openQuickMenu(quickMenuRow)
            }
        } else {
            settingsViewModel.cancel()
            nav.pop()
        }
    }

    override fun onNorth() {
        val item = settingsViewModel.getSelectedItem()
        if (item?.key == "rom_directory" && settings.romDirectory.isNotEmpty()) {
            launcherActions.confirmRomDirectoryChange("")
            return
        }
        if (settingsViewModel.state.value.activeCategory == "screen_geometry") {
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
        val initial = emulatorMappingBuilder.detailedMappings()
        nav.push(LauncherScreen.EmulatorMapping(mappings = initial, allMappings = initial))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            withContext(Dispatchers.Main) {
                val cm = nav.screenStack.lastOrNull() as? LauncherScreen.EmulatorMapping ?: return@withContext
                val all = emulatorMappingBuilder.detailedMappings()
                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(
                    mappings = emulatorMappingBuilder.filter(all, cm.filter),
                    allMappings = all
                )
            }
        }
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

    private fun queryInstalledCores() {
        nav.push(LauncherScreen.InstalledCores(
            title = context.getString(dev.cannoli.scorza.R.string.title_installed_cores_all)
        ))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            // Every install's cores, each labelled with the install it came from, since no single
            // RetroArch is "the" one any more.
            val cores = installedCoreService.externalRaCores()
                .flatMap { (pkg, ids) ->
                    val label = InstalledCoreService.getPackageLabel(pkg)
                    ids.map { "${platformConfig.getCoreDisplayName(it)} ($label)" }
                }
                .sorted()
            withContext(Dispatchers.Main) {
                val screen = nav.screenStack.lastOrNull() as? LauncherScreen.InstalledCores ?: return@withContext
                nav.screenStack[nav.screenStack.lastIndex] = screen.copy(cores = cores, loading = false)
            }
        }
    }

    private fun openAppPicker(type: String) {
        val installed = getInstalledLauncherApps()
        val allApps = buildList {
            if (type == "tools" && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                add("Android TV Settings" to ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE)
            }
            addAll(installed)
        }
        val appType = if (type == "tools") AppType.TOOL else AppType.PORT
        val existing = appsRepository.all(appType).map { it.packageName }.toSet()
        val initialChecked = allApps.indices.filter { allApps[it].second in existing }.toSet()
        val title = if (type == "tools") "Manage Tools" else "Manage Ports"
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
        val appType = if (state.type == "tools") AppType.TOOL else AppType.PORT
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
