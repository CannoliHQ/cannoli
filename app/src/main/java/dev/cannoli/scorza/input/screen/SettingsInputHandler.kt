package dev.cannoli.scorza.input.screen

import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsInputHandler(
    private val nav: NavigationController,
    private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val profileManager: ProfileManager,
    private val globalOverrides: GlobalOverridesManager,
    private val appsRepository: AppsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val inputTesterController: InputTesterController,
    private val updateManager: UpdateManager,
    private val settingsViewModel: SettingsViewModel,
    private val onRescanSystemList: () -> Unit,
    private val onInvalidateAllLibraryCaches: () -> Unit,
    private val onOpenColorPicker: (settingKey: String) -> Unit,
    private val onStartRaLogin: (username: String, password: String) -> Unit,
    private val context: android.content.Context,
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
            val cat = settingsViewModel.state.value.categories.getOrNull(settingsViewModel.state.value.categoryIndex)
            when {
                cat?.key == "about" -> nav.dialogState.value = DialogState.About()
                cat?.key == "retroachievements" && settings.raToken.isNotEmpty() ->
                    nav.dialogState.value = DialogState.RAAccount(username = settings.raUsername)
                cat?.key == "kitchen" -> {
                    val root = java.io.File(settings.sdCardRoot)
                    val km = dev.cannoli.scorza.server.KitchenManager
                    if (!km.isRunning) km.toggle(root, context.assets, settings.kitchenCodeBypass)
                    else km.setCodeBypass(settings.kitchenCodeBypass)
                    nav.dialogState.value = DialogState.Kitchen(
                        urls = km.getUrls(hasActiveVpn()),
                        pin = km.pin,
                        requirePin = !settings.kitchenCodeBypass
                    )
                }
                else -> settingsViewModel.enterCategory()
            }
            return
        }

        when (val key = settingsViewModel.enterSelected()) {
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
            "profiles" -> nav.push(LauncherScreen.ProfileList(profiles = profileManager.listProfiles()))
            "shortcuts" -> nav.push(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
            "input_tester" -> {
                inputTesterController.enter()
                nav.push(LauncherScreen.InputTester)
            }
            "core_mapping" -> openCoreMapping()
            "set_default_launcher" -> context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "installed_cores" -> queryInstalledCores()
            "rebuild_cache" -> {
                onInvalidateAllLibraryCaches()
                onRescanSystemList()
            }
            "manage_tools" -> openAppPicker("tools")
            "manage_ports" -> openAppPicker("ports")
            "ra_username" -> {
                val current = settings.raUsername
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_username",
                    currentName = current,
                    cursorPos = current.length
                )
            }
            "ra_password" -> {
                nav.dialogState.value = DialogState.RenameInput(
                    gameName = "ra_password",
                    currentName = settingsViewModel.raPassword,
                    cursorPos = settingsViewModel.raPassword.length
                )
            }
            "ra_login" -> onStartRaLogin(settings.raUsername, settingsViewModel.raPassword)
            null -> {}
            else -> {
                when {
                    key.startsWith("fgh_pick:") -> {
                        val stem = key.removePrefix("fgh_pick:")
                        settingsViewModel.selectFghCollectionStem(stem)
                        settingsViewModel.save()
                        settingsViewModel.exitSubList()
                        onRescanSystemList()
                    }
                    key.startsWith("color_") -> {
                        val entries = settingsViewModel.getColorEntries()
                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                        nav.push(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                        onOpenColorPicker(key)
                    }
                    else -> {
                        val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = key,
                            currentName = displayValue,
                            cursorPos = displayValue.length
                        )
                    }
                }
            }
        }
    }

    override fun onBack() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.save()
            settingsViewModel.exitSubList()
            onRescanSystemList()
        } else {
            settingsViewModel.cancel()
            nav.pop()
        }
    }

    override fun onNorth() {
        val item = settingsViewModel.getSelectedItem()
        if (item?.key == "rom_directory" && settings.romDirectory.isNotEmpty()) {
            settingsViewModel.clearRomDirectory()
            onInvalidateAllLibraryCaches()
            nav.dialogState.value = DialogState.RestartRequired
        }
    }

    private fun pageJump(direction: Int) {
        val state = settingsViewModel.state.value
        val itemCount = state.categories.size
        if (itemCount == 0) return
        val lastIndex = itemCount - 1
        val page = nav.currentPageSize.coerceAtLeast(1)

        val newIdx: Int
        val newScroll: Int

        if (direction > 0) {
            val lastVisible = nav.currentFirstVisible + page - 1
            if (lastVisible >= lastIndex) {
                if (state.categoryIndex >= lastIndex) return
                newIdx = lastIndex
                newScroll = nav.currentFirstVisible
            } else {
                newIdx = (nav.currentFirstVisible + page).coerceAtMost(lastIndex)
                newScroll = newIdx
            }
        } else {
            if (nav.currentFirstVisible <= 0) {
                if (state.categoryIndex <= 0) return
                newIdx = 0
                newScroll = 0
            } else {
                newIdx = (nav.currentFirstVisible - page).coerceAtLeast(0)
                newScroll = newIdx
            }
        }

        settingsViewModel.setCategoryIndex(newIdx)
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        nav.push(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun openCoreMapping() {
        val initial = platformConfig.getDetailedMappings(
            context.packageManager,
            installedCoreService.installedCores,
            LaunchManager.extractBundledCores(context),
            installedCoreService.unresponsivePackages
        )
        nav.push(LauncherScreen.CoreMapping(mappings = initial, allMappings = initial))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            withContext(Dispatchers.Main) {
                val cm = nav.screenStack.lastOrNull() as? LauncherScreen.CoreMapping ?: return@withContext
                val all = platformConfig.getDetailedMappings(
                    context.packageManager,
                    installedCoreService.installedCores,
                    LaunchManager.extractBundledCores(context),
                    installedCoreService.unresponsivePackages
                )
                nav.screenStack[nav.screenStack.lastIndex] = cm.copy(
                    mappings = filterCoreMappings(all, cm.filter),
                    allMappings = all
                )
            }
        }
    }

    private fun queryInstalledCores() {
        val selectedPkg = settings.retroArchPackage
        val pkgLabel = InstalledCoreService.getPackageLabel(selectedPkg)
        nav.push(LauncherScreen.InstalledCores(title = "$pkgLabel Installed Cores"))
        ioScope.launch {
            installedCoreService.queryAllPackages()
            val cores = (installedCoreService.installedCores[selectedPkg] ?: emptySet())
                .map { coreId -> platformConfig.getCoreDisplayName(coreId) }
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

    private fun filterCoreMappings(all: List<CoreMappingEntry>, filter: Int): List<CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
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
            onRescanSystemList()
        }
        nav.pop()
    }

    private fun hasActiveVpn(): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }
}
