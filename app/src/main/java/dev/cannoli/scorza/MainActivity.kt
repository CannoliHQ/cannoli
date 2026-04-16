package dev.cannoli.scorza

import android.Manifest
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.igm.ELLIPSIS
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.scorza.scanner.CollectionManager
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.OrderingManager
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.scanner.RecentlyPlayedManager
import androidx.lifecycle.lifecycleScope
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.COLOR_GRID_COLS
import dev.cannoli.scorza.ui.components.handleKeyboardConfirm
import dev.cannoli.scorza.ui.components.HEX_KEYS
import dev.cannoli.scorza.ui.components.CREDITS
import dev.cannoli.scorza.ui.components.HEX_ROW_SIZE
import dev.cannoli.scorza.ui.components.getKeyboardRows
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.ui.screens.asKeyboardState
import dev.cannoli.scorza.ui.screens.withBackspace
import dev.cannoli.scorza.ui.screens.withCaps
import dev.cannoli.scorza.ui.screens.withSymbols
import dev.cannoli.scorza.ui.screens.withCursor
import dev.cannoli.scorza.ui.screens.withInsertedChar
import dev.cannoli.scorza.ui.screens.withKeyboard
import dev.cannoli.scorza.ui.screens.withMenuDelta
import dev.cannoli.igm.ui.theme.COLOR_PRESETS
import dev.cannoli.igm.ui.theme.CannoliTheme
import dev.cannoli.igm.ui.theme.colorToArgbLong
import dev.cannoli.igm.ui.theme.hexToColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.InstallingScreen
import dev.cannoli.scorza.ui.screens.PermissionScreen
import dev.cannoli.scorza.ui.screens.SetupScreen
import dev.cannoli.igm.ui.components.pillItemHeight
import dev.cannoli.igm.ui.theme.initFonts
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var platformResolver: PlatformResolver
    private lateinit var scanner: FileScanner
    private lateinit var collectionManager: CollectionManager
    private lateinit var recentlyPlayedManager: RecentlyPlayedManager
    private lateinit var orderingManager: OrderingManager
    private lateinit var systemListViewModel: SystemListViewModel
    private lateinit var gameListViewModel: GameListViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private val inputTesterViewModel = dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel()
    private lateinit var controllerManager: dev.cannoli.scorza.input.ControllerManager
    private lateinit var updateManager: dev.cannoli.scorza.updater.UpdateManager

    private lateinit var retroArchLauncher: RetroArchLauncher
    private lateinit var emuLauncher: EmuLauncher
    private lateinit var apkLauncher: ApkLauncher
    private lateinit var installedCoreService: InstalledCoreService

    private lateinit var inputHandler: InputHandler
    private lateinit var atomicRename: AtomicRename

    private val screenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)
    private val currentScreen: LauncherScreen get() = screenStack.lastOrNull() ?: LauncherScreen.SystemList
    private var resumableGames by mutableStateOf(emptySet<String>())
    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlButtons = LibretroInput().buttons
    private val controlButtonCount = controlButtons.size
    private val selectHoldHandler = Handler(Looper.getMainLooper())
    private var selectDown = false
    private var selectHeld = false
    private var selectHandled = false
    private var capsBeforeSymbols = false
    private val selectHoldRunnable = Runnable {
        selectHeld = true
        val ds = dialogState.value
        if (ds is KeyboardInputState) {
            val ks = ds.asKeyboardState()!!
            if (!ks.symbols) capsBeforeSymbols = ks.caps
            dialogState.value = ds.withCaps(false).withSymbols(!ks.symbols)
        }
    }
    private val collectionSelectHoldRunnable = Runnable {
        selectHeld = true
        val glState = gameListViewModel.state.value
        val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
        if ((glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) || isApkList) {
            if (!gameListViewModel.isReorderMode() && !gameListViewModel.isMultiSelectMode()) {
                gameListViewModel.enterMultiSelect()
            }
        }
    }
    @Volatile private var navigating = false
    private var pendingRecentlyPlayedReorder = false
    private var coreQueryReceiver: android.content.BroadcastReceiver? = null
    private var loginManager: RetroAchievementsManager? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
    private var currentFirstVisible = 0
    private var currentPageSize = 10
    private var permissionGranted by mutableStateOf(false)
    private var coldStart = true

    private val shortcutCountdownHandler = Handler(Looper.getMainLooper())
    private val shortcutHoldMs = 1500
    private val shortcutTickMs = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + shortcutTickMs.toInt()
            if (newMs >= shortcutHoldMs) {
                val action = ShortcutAction.entries.getOrNull(screen.selectedIndex) ?: return
                val chord = screen.heldKeys
                val cleared = screen.shortcuts.filterValues { it != chord }
                screenStack[screenStack.lastIndex] = screen.copy(
                    shortcuts = cleared + (action to chord),
                    listening = false, heldKeys = emptySet(), countdownMs = 0
                )
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(countdownMs = newMs)
                shortcutCountdownHandler.postDelayed(this, shortcutTickMs)
            }
        }
    }

    private fun cancelShortcutListening() {
        shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
        val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
        if (screen.listening) {
            screenStack[screenStack.lastIndex] = screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0)
        }
    }

    private val controlListenTimeoutMs = 3000
    private val controlListenTickMs = 100L

    private val controlListenRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ControlBinding ?: return
            if (screen.listeningIndex < 0) return
            val newMs = screen.listenCountdownMs + controlListenTickMs.toInt()
            if (newMs >= controlListenTimeoutMs) {
                screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = -1, listenCountdownMs = 0)
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(listenCountdownMs = newMs)
                shortcutCountdownHandler.postDelayed(this, controlListenTickMs)
            }
        }
    }

    private lateinit var globalOverrides: GlobalOverridesManager
    private lateinit var profileManager: dev.cannoli.scorza.input.ProfileManager
    private lateinit var autoconfigLoader: dev.cannoli.scorza.input.autoconfig.AutoconfigLoader
    private lateinit var autoconfigMatcher: dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher
    private lateinit var launchManager: LaunchManager

    private fun pushScreen(new: LauncherScreen) {
        val current = currentScreen
        screenStack[screenStack.lastIndex] = saveScrollPosition(current)
        screenStack.add(new)
    }

    private fun saveScrollPosition(screen: LauncherScreen): LauncherScreen = when (screen) {
        is LauncherScreen.CoreMapping -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CorePicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ColorList -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CollectionPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ChildPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.AppPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ProfileList -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ControlBinding -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ShortcutBinding -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.Credits -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.InstalledCores -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.DirectoryBrowser -> screen.copy(scrollTarget = currentFirstVisible)
        else -> screen
    }

    private fun pageJump(direction: Int) {
        val page = currentPageSize.coerceAtLeast(1)

        val screen = currentScreen
        val (itemCount, selectedIndex) = when (screen) {
            LauncherScreen.SystemList -> systemListViewModel.state.value.let { it.items.size to it.selectedIndex }
            LauncherScreen.GameList -> gameListViewModel.state.value.let { it.games.size to it.selectedIndex }
            LauncherScreen.Settings -> settingsViewModel.state.value.let { it.categories.size to it.categoryIndex }
            is LauncherScreen.CoreMapping -> screen.mappings.size to screen.selectedIndex
            is LauncherScreen.CorePicker -> screen.cores.size to screen.selectedIndex
            is LauncherScreen.ColorList -> screen.colors.size to screen.selectedIndex
            is LauncherScreen.CollectionPicker -> screen.collections.size to screen.selectedIndex
            is LauncherScreen.ChildPicker -> screen.collections.size to screen.selectedIndex
            is LauncherScreen.AppPicker -> screen.apps.size to screen.selectedIndex
            is LauncherScreen.ProfileList -> screen.profiles.size to screen.selectedIndex
            is LauncherScreen.ControlBinding -> controlButtonCount to screen.selectedIndex
            is LauncherScreen.ShortcutBinding -> ShortcutAction.entries.size to screen.selectedIndex
            is LauncherScreen.Credits -> CREDITS.size to screen.selectedIndex
            is LauncherScreen.InstalledCores -> screen.cores.size to screen.selectedIndex
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                (screen.entries.size + if (hasSelect) 1 else 0) to screen.selectedIndex
            }
            is LauncherScreen.InputTester,
            is LauncherScreen.Setup,
            is LauncherScreen.Installing -> return
        }

        if (itemCount == 0) return
        val lastIndex = itemCount - 1

        val newIdx: Int
        val newScroll: Int

        if (direction > 0) {
            val lastVisible = currentFirstVisible + page - 1
            if (lastVisible >= lastIndex) {
                if (selectedIndex >= lastIndex) return
                newIdx = lastIndex
                newScroll = currentFirstVisible
            } else {
                newIdx = (currentFirstVisible + page).coerceAtMost(lastIndex)
                newScroll = newIdx
            }
        } else {
            if (currentFirstVisible <= 0) {
                if (selectedIndex <= 0) return
                newIdx = 0
                newScroll = 0
            } else {
                newIdx = (currentFirstVisible - page).coerceAtLeast(0)
                newScroll = newIdx
            }
        }

        applyPageJump(screen, newIdx, newScroll)
    }

    private fun applyPageJump(screen: LauncherScreen, newIdx: Int, newScroll: Int) {
        when (screen) {
            LauncherScreen.SystemList -> systemListViewModel.jumpToIndex(newIdx, newScroll)
            LauncherScreen.GameList -> gameListViewModel.jumpToIndex(newIdx, newScroll)
            LauncherScreen.Settings -> settingsViewModel.setCategoryIndex(newIdx)
            is LauncherScreen.CoreMapping -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.CorePicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ColorList -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.CollectionPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ChildPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.AppPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ProfileList -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ControlBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ShortcutBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.Credits -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.InstalledCores -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.DirectoryBrowser -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.InputTester,
            is LauncherScreen.Setup,
            is LauncherScreen.Installing -> {}
        }
    }

    private fun updateColorListOnStack(settingKey: String, entries: List<ColorEntry>) {
        val cl = currentScreen
        if (cl is LauncherScreen.ColorList) {
            screenStack[screenStack.lastIndex] = cl.copy(
                colors = entries,
                selectedIndex = entries.indexOfFirst { it.key == settingKey }.coerceAtLeast(0)
            )
        }
    }

    private fun refreshCollectionPickerOnStack() {
        val cp = currentScreen
        if (cp is LauncherScreen.CollectionPicker) {
            val all = collectionManager.scanCollections()
                .filter { !it.stem.equals("Favorites", ignoreCase = true) }
            val stems = all.map { it.stem }
            val displayNames = all.map { it.displayName }
            val alreadyIn = if (cp.gamePaths.size == 1) {
                collectionManager.getCollectionsContaining(cp.gamePaths[0])
            } else {
                cp.gamePaths.map { collectionManager.getCollectionsContaining(it) }
                    .reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
            }
            val newInitialChecked = stems.indices
                .filter { stems[it] in alreadyIn }
                .toSet()
            val oldCheckedStems = cp.checkedIndices.mapNotNull { cp.collections.getOrNull(it) }.toSet()
            val newCheckedIndices = stems.indices
                .filter { stems[it] in oldCheckedStems || stems[it] in alreadyIn }
                .toSet()
            screenStack[screenStack.lastIndex] = cp.copy(
                collections = stems,
                displayNames = displayNames,
                checkedIndices = newCheckedIndices,
                initialChecked = newInitialChecked
            )
        }
    }

    // Tracks context menu to return to after sub-dialog actions
    private sealed interface ContextReturn {
        data class Single(val gameName: String, val options: List<String>, val selectedOption: Int = 0) : ContextReturn
        data class Bulk(val gamePaths: List<String>, val options: List<String>) : ContextReturn
    }
    private var pendingContextReturn: ContextReturn? = null
    private var pendingFghGame: dev.cannoli.scorza.model.Game? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
        }
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            permissionGranted = true
            afterPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        super.onCreate(savedInstanceState)

        hideSystemUI()

        settings = SettingsRepository(this)
        initFonts(assets)

        settingsViewModel = SettingsViewModel(settings)
        profileManager = dev.cannoli.scorza.input.ProfileManager(settings.sdCardRoot)
        autoconfigLoader = dev.cannoli.scorza.input.autoconfig.AutoconfigLoader(
            dev.cannoli.scorza.input.autoconfig.AssetCfgSource(this)
        )
        autoconfigMatcher = dev.cannoli.scorza.input.autoconfig.AutoconfigMatcher(autoconfigLoader.entries())
        inputHandler = InputHandler(
            getButtonMappings = {
                val screen = screenStack.lastOrNull() as? LauncherScreen.ControlBinding
                if (screen != null && screen.profileName == dev.cannoli.scorza.input.ProfileManager.NAVIGATION) screen.controls
                else profileManager.readControls(dev.cannoli.scorza.input.ProfileManager.NAVIGATION)
            }
        )
        wireInput()

        lifecycleScope.launch {
            settingsViewModel.appSettings.collect { appSettings ->
                inputHandler.swapConfirmBack = appSettings.confirmButton == dev.cannoli.igm.ConfirmButton.EAST
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        if (hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
        }

        setContent {
            val appFont by settingsViewModel.appSettings.collectAsState()
            CannoliTheme(fontFamily = appFont.fontFamily) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionGranted) {
                        PermissionScreen()
                    } else {
                        val updateInfo = if (::updateManager.isInitialized) updateManager.updateAvailable.collectAsState().value else null
                        val dlProgress = if (::updateManager.isInitialized) updateManager.downloadProgress.collectAsState().value else 0f
                        val dlError = if (::updateManager.isInitialized) updateManager.downloadError.collectAsState().value else null
                        AppNavGraph(
                            currentScreen = currentScreen,
                            systemListViewModel = if (::systemListViewModel.isInitialized) systemListViewModel else null,
                            gameListViewModel = if (::gameListViewModel.isInitialized) gameListViewModel else null,
                            inputTesterViewModel = inputTesterViewModel,
                            onExitInputTester = { if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex) },
                            settingsViewModel = settingsViewModel,
                            dialogState = dialogState,
                            onVisibleRangeChanged = { first, count, full ->
                                currentFirstVisible = first
                                if (full) currentPageSize = count
                            },
                            resumableGames = resumableGames,
                            updateAvailable = updateInfo != null,
                            downloadProgress = dlProgress ?: 0f,
                            downloadError = dlError
                        )
                    }
                }
            }
        }
    }

    private fun afterPermissionGranted() {
        if (settings.setupCompleted) {
            initializeApp()
        } else if (File(settings.sdCardRoot, "Config/settings.json").exists()) {
            settings.setupCompleted = true
            initializeApp()
        } else {
            val detected = detectExistingCannoli()
            if (detected != null) {
                settings.sdCardRoot = detected
                settings.setupCompleted = true
                initializeApp()
            } else {
                val volumes = detectStorageVolumes() + ("Custom" to "")
                screenStack.clear()
                screenStack.add(LauncherScreen.Setup(volumes = volumes))
            }
        }
    }

    private fun detectExistingCannoli(): String? {
        val volumes = detectStorageVolumes()
        for ((_, path) in volumes.reversed()) {
            val cannoli = File(path, "Cannoli")
            if (cannoli.exists() && cannoli.isDirectory && File(cannoli, "Config/settings.json").exists()) {
                return cannoli.absolutePath + "/"
            }
        }
        return null
    }

    private fun detectStorageVolumes(): List<Pair<String, String>> {
        val volumes = mutableListOf("Internal Storage" to "/storage/emulated/0/")
        val sm = getSystemService(android.os.storage.StorageManager::class.java)
        for (sv in sm.storageVolumes) {
            if (sv.isPrimary) continue
            val path = if (android.os.Build.VERSION.SDK_INT >= 30) {
                sv.directory?.absolutePath
            } else {
                try { sv.javaClass.getMethod("getPath").invoke(sv) as? String } catch (_: Exception) { null }
            } ?: continue
            val label = sv.getDescription(this) ?: File(path).name
            volumes.add(label to "$path/")
        }
        if (volumes.size == 1) {
            val storageDir = File("/storage")
            storageDir.listFiles()?.forEach { dir ->
                if (dir.name != "emulated" && dir.name != "self" && dir.isDirectory && dir.canRead()) {
                    volumes.add(dir.name to dir.absolutePath + "/")
                }
            }
        }
        return volumes
    }

    private var volumeMap: Map<String, String> = emptyMap()

    private fun listDirectories(path: String): List<String> {
        if (path == "/storage/") {
            val volumes = detectStorageVolumes()
            volumeMap = volumes.associate { (label, volPath) -> label to volPath }
            return volumes.map { it.first }
        }
        val dir = java.io.File(path)
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.map { it.name }
            ?.sortedWith(dev.cannoli.scorza.util.NaturalSort)
            ?: emptyList()
    }

    private fun resolveDirectoryEntry(currentPath: String, entryName: String): String {
        if (currentPath == "/storage/") {
            return volumeMap[entryName] ?: "/storage/$entryName/"
        }
        return currentPath + entryName + "/"
    }

    private fun parentDirectory(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed == "/storage") return null
        if (volumeMap.values.any { it.trimEnd('/') == trimmed }) return "/storage/"
        return if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else null
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = listDirectories(startPath)
        screenStack.add(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun onDirectoryBrowserResult(purpose: BrowsePurpose, path: String) {
        val resolved = if (isVolumeRoot(path)) path + "Cannoli/" else path
        when (purpose) {
            BrowsePurpose.SD_ROOT -> {
                settings.sdCardRoot = resolved
                dialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.ROM_DIRECTORY -> {
                settings.romDirectory = resolved
                scanner.invalidateAllCaches()
                dialogState.value = DialogState.RestartRequired
            }
            BrowsePurpose.SETUP -> {
                val idx = screenStack.indexOfLast { it is LauncherScreen.Setup }
                if (idx >= 0) {
                    val setup = screenStack[idx] as LauncherScreen.Setup
                    val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                    screenStack[idx] = setup.copy(customPath = path)
                }
            }
        }
    }

    private fun startInstalling(targetPath: String) {
        val labels = listOf(
            "Kneading the dough$ELLIPSIS",
            "Rolling the shells$ELLIPSIS",
            "Heating the oil$ELLIPSIS",
            "Frying the shells$ELLIPSIS",
            "Making the filling$ELLIPSIS",
            "Piping the rigott$ELLIPSIS"
        )

        ioScope.launch {
            val root = File(targetPath)
            val coreInfo = dev.cannoli.scorza.scanner.CoreInfoRepository(assets, filesDir, File(applicationInfo.sourceDir).lastModified())
            coreInfo.load()
            val bundledCoresDir = LaunchManager.extractBundledCores(this@MainActivity)
            platformResolver = PlatformResolver(root, assets, coreInfo, bundledCoresDir)
            platformResolver.load()

            collectionManager = CollectionManager(root)
            recentlyPlayedManager = RecentlyPlayedManager(root)
            orderingManager = OrderingManager(root)
            val localScanner = FileScanner(root, platformResolver, collectionManager, assets)
            localScanner.loadIgnoreExtensions()
            localScanner.loadIgnoreFiles()

            val overhead = 7
            val dirCount = 18 + (platformResolver.getAllTags().size * 6)
            val totalSteps = dirCount + overhead
            var completed = 0

            fun step() {
                completed++
                val p = completed.toFloat() / totalSteps
                val labelIndex = (p * labels.size).toInt().coerceIn(0, labels.lastIndex)
                val screen = screenStack.lastOrNull() as? LauncherScreen.Installing ?: return
                screenStack[screenStack.lastIndex] = screen.copy(progress = p, statusLabel = labels[labelIndex])
            }

            localScanner.ensureDirectories { step() }

            retroArchLauncher = RetroArchLauncher(this@MainActivity) { settings.retroArchPackage }; step()
            emuLauncher = EmuLauncher(this@MainActivity); step()
            apkLauncher = ApkLauncher(this@MainActivity); step()
            installedCoreService = InstalledCoreService(this@MainActivity); step()
            val lm = LaunchManager(this@MainActivity, settings, platformResolver, retroArchLauncher, emuLauncher, apkLauncher, installedCoreService); step()
            lm.syncRetroArchAssets(root); step()
            lm.syncRetroArchConfig(root); step()

            this@MainActivity.scanner = localScanner
            launchManager = lm

            withContext(Dispatchers.Main) {
                val screen = screenStack.lastOrNull() as? LauncherScreen.Installing ?: return@withContext
                screenStack[screenStack.lastIndex] = screen.copy(
                    progress = 1f,
                    statusLabel = "Cannoli is now ready to be garnished!",
                    finished = true
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (!permissionGranted && hasStoragePermission()) {
            permissionGranted = true
            afterPermissionGranted()
            return
        }
        if (!coldStart) overridePendingTransition(0, 0)
        coldStart = false
        hideSystemUI()
        if (::launchManager.isInitialized) launchManager.launching = false
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, opts)
            return
        }
        if (::settings.isInitialized) {
            settings.reload()
            if (::settingsViewModel.isInitialized) settingsViewModel.load()
            if (dialogState.value is DialogState.RAAccount && settings.raToken.isEmpty()) {
                dialogState.value = DialogState.None
            }
        }
        if (::systemListViewModel.isInitialized) {
            rescanSystemList()
            if (screenStack.lastOrNull() is LauncherScreen.GameList) {
                gameListViewModel.reload { scanResumableGames() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (pendingRecentlyPlayedReorder) {
            pendingRecentlyPlayedReorder = false
            gameListViewModel.moveSelectedToTop()
        }
    }

    override fun onDestroy() {
        if (::controllerManager.isInitialized) {
            (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
                .unregisterInputDeviceListener(controllerManager)
        }
        super.onDestroy()
        unregisterCoreQueryReceiver()
        settings.shutdown()
        ioScope.cancel()
        if (::systemListViewModel.isInitialized) systemListViewModel.close()
        if (::gameListViewModel.isInitialized) gameListViewModel.close()
        dev.cannoli.scorza.server.KitchenManager.stop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (screenStack.lastOrNull() is LauncherScreen.InputTester) {
                routeKeyToInputTester(event, down = event.action == KeyEvent.ACTION_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!permissionGranted) {
            requestStoragePermission()
            return true
        }
        if (screenStack.lastOrNull() is LauncherScreen.InputTester) {
            routeKeyToInputTester(event, down = true)
            return true
        }
        if (handleBindingKeyDown(keyCode)) {
            return true
        }
        val screen = screenStack.lastOrNull()
        if (event.repeatCount > 0 && screen is LauncherScreen.ShortcutBinding && !screen.listening) {
            return true
        }
        if (inputHandler.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (screenStack.lastOrNull() is LauncherScreen.InputTester) {
            routeKeyToInputTester(event, down = false)
            return true
        }
        if (inputHandler.resolveButton(keyCode) == "btn_select") {
            selectHoldHandler.removeCallbacks(selectHoldRunnable)
            selectHoldHandler.removeCallbacks(collectionSelectHoldRunnable)
            if (!selectHeld && dialogState.value is KeyboardInputState) {
                val ds = dialogState.value
                val ks = ds.asKeyboardState()!!
                if (ks.symbols) {
                    dialogState.value = ds.withCaps(capsBeforeSymbols).withSymbols(false)
                } else {
                    dialogState.value = ds.withCaps(!ks.caps)
                }
            } else if (!selectHeld && !selectHandled && dialogState.value == DialogState.None
                && currentScreen == LauncherScreen.GameList) {
                val glState = gameListViewModel.state.value
                val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
                if (((glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) || isApkList)
                    && !gameListViewModel.isReorderMode() && !gameListViewModel.isMultiSelectMode()) {
                    gameListViewModel.enterReorderMode()
                }
            }
            selectDown = false
            selectHeld = false
            selectHandled = false
            return true
        }
        val screen = screenStack.lastOrNull()
        if (screen is LauncherScreen.ShortcutBinding && screen.listening && screen.heldKeys.contains(keyCode)) {
            cancelShortcutListening()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val source = event.source
        val isJoystick =
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        if (screenStack.lastOrNull() is LauncherScreen.InputTester) {
            val deviceId = event.deviceId
            val port = controllerManager.getPortForDeviceId(deviceId) ?: 0
            val name = event.device?.name ?: getString(R.string.input_tester_device_unknown)
            val leftX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
            val leftY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            val rightX = event.getAxisValue(android.view.MotionEvent.AXIS_Z)
            val rightY = event.getAxisValue(android.view.MotionEvent.AXIS_RZ)
            val leftTrigger = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE),
            )
            val rightTrigger = maxOf(
                event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(android.view.MotionEvent.AXIS_GAS),
            )
            val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
            inputTesterViewModel.onMotion(
                port = port, deviceId = deviceId, deviceName = name,
                leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
                leftTrigger = leftTrigger, rightTrigger = rightTrigger,
                hatX = hatX, hatY = hatY,
            )

            if (testerSelectHeld) {
                val dir = when {
                    hatX < -0.5f || leftX < -0.5f -> -1
                    hatX >  0.5f || leftX >  0.5f ->  1
                    else -> 0
                }
                if (dir != 0 && dir != testerHatChordState) {
                    releaseAllTesterKeys(except = setOf("btn_select"))
                    val newProfile = inputTesterViewModel.cycleProfile(
                        forward = dir == 1,
                        keepPressed = setOf("btn_select"),
                    )
                    loadTesterProfile(newProfile)
                }
                testerHatChordState = dir
            } else {
                testerHatChordState = 0
            }

            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    private var testerProfileMap: Map<Int, String> = emptyMap()
    private val testerPressedKeycodes = mutableMapOf<Int, String?>()
    private var testerSelectHeld = false
    private var testerStartHeld = false
    private var testerHatChordState: Int = 0
    private val testerExitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val testerExitRunnable = Runnable { inputTesterViewModel.requestExit() }

    private fun updateTesterExitCountdown() {
        if (testerSelectHeld && testerStartHeld) {
            testerExitHandler.removeCallbacks(testerExitRunnable)
            testerExitHandler.postDelayed(testerExitRunnable, 1250L)
        } else {
            testerExitHandler.removeCallbacks(testerExitRunnable)
        }
    }

    private fun loadTesterProfile(name: String) {
        val controls = profileManager.readControls(name)
        val profileInverse = controls.entries.associate { (prefKey, keyCode) -> keyCode to prefKey }
        testerProfileMap = dev.cannoli.scorza.input.InputHandler.DEFAULT_KEY_MAP + profileInverse
    }

    private fun initTesterProfiles() {
        val profiles = profileManager.listProfiles()
        val initial = profiles.firstOrNull() ?: dev.cannoli.scorza.input.ProfileManager.NAVIGATION
        inputTesterViewModel.setProfiles(profiles, initial)
        loadTesterProfile(initial)
        testerPressedKeycodes.clear()
        testerSelectHeld = false
        testerStartHeld = false
        testerExitHandler.removeCallbacks(testerExitRunnable)
    }

    private fun releaseAllTesterKeys(except: Set<String> = emptySet()) {
        val snapshot = testerPressedKeycodes.toMap()
        for ((kc, resolved) in snapshot) {
            if (resolved in except) continue
            val keyName = KeyEvent.keyCodeToString(kc).removePrefix("KEYCODE_")
            inputTesterViewModel.onKeyUp(0, kc, keyName, -1, "", resolved)
            testerPressedKeycodes.remove(kc)
        }
    }

    private fun routeKeyToInputTester(event: KeyEvent, down: Boolean) {
        val device = event.device
        val deviceId = event.deviceId
        val port = if (device != null) controllerManager.getPortForDeviceId(deviceId) ?: 0 else 0
        val name = device?.name ?: getString(R.string.input_tester_device_keyboard)
        val keyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        val navButton = inputHandler.resolveButton(event.keyCode)

        if (down) {
            val isRepeat = event.repeatCount > 0
            if (navButton == "btn_select" && !testerSelectHeld) {
                testerSelectHeld = true
                updateTesterExitCountdown()
            }
            if (navButton == "btn_start" && !testerStartHeld) {
                testerStartHeld = true
                updateTesterExitCountdown()
            }
            if (!isRepeat && testerSelectHeld && (navButton == "btn_left" || navButton == "btn_right")) {
                releaseAllTesterKeys(except = setOf("btn_select"))
                val newProfile = inputTesterViewModel.cycleProfile(
                    forward = navButton == "btn_right",
                    keepPressed = setOf("btn_select"),
                )
                loadTesterProfile(newProfile)
            }
            val resolved = testerProfileMap[event.keyCode]
            testerPressedKeycodes[event.keyCode] = resolved
            inputTesterViewModel.onKeyDown(port, event.keyCode, keyName, deviceId, name, resolved)
            if (!isRepeat) inputTesterViewModel.setActivePort(port)
        } else {
            if (navButton == "btn_select" && testerSelectHeld) {
                testerSelectHeld = false
                updateTesterExitCountdown()
            }
            if (navButton == "btn_start" && testerStartHeld) {
                testerStartHeld = false
                updateTesterExitCountdown()
            }
            val resolved = testerPressedKeycodes.remove(event.keyCode)
            inputTesterViewModel.onKeyUp(port, event.keyCode, keyName, deviceId, name, resolved)
        }
        refreshInputTesterPorts()
    }

    private fun refreshInputTesterPorts() {
        val slots = controllerManager.slots
        val ports = slots.indices.mapNotNull { i ->
            val slot = slots[i] ?: return@mapNotNull null
            dev.cannoli.scorza.ui.viewmodel.DeviceInfo(
                port = i,
                deviceId = controllerManager.getDeviceIdForPort(i) ?: -1,
                name = slot.name,
            )
        }
        inputTesterViewModel.setConnectedPorts(ports)
    }

    private fun handleBindingKeyDown(keyCode: Int): Boolean {
        val screen = screenStack.lastOrNull() ?: return false
        when (screen) {
            is LauncherScreen.ControlBinding -> {
                if (screen.listeningIndex < 0 || screen.listeningIndex >= controlButtons.size) return false
                shortcutCountdownHandler.removeCallbacks(controlListenRunnable)
                val btn = controlButtons[screen.listeningIndex]
                screenStack[screenStack.lastIndex] = screen.copy(
                    controls = screen.controls + (btn.prefKey to keyCode),
                    listeningIndex = -1, listenCountdownMs = 0
                )
                return true
            }
            is LauncherScreen.ShortcutBinding -> {
                if (!screen.listening) return false
                if (screen.heldKeys.contains(keyCode)) return true
                val newKeys = screen.heldKeys + keyCode
                screenStack[screenStack.lastIndex] = screen.copy(heldKeys = newKeys, countdownMs = 0)
                shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
                shortcutCountdownHandler.postDelayed(shortcutCountdownRunnable, shortcutTickMs)
                return true
            }
            else -> return false
        }
    }

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)
        dev.cannoli.scorza.util.DebugLog.init(root.absolutePath)

        retroArchLauncher = RetroArchLauncher(this) { settings.retroArchPackage }
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        val coreInfo = dev.cannoli.scorza.scanner.CoreInfoRepository(assets, filesDir, File(applicationInfo.sourceDir).lastModified())
        coreInfo.load()
        val bundledCoresDir = LaunchManager.extractBundledCores(this)
        platformResolver = PlatformResolver(root, assets, coreInfo, bundledCoresDir)
        platformResolver.load()

        installedCoreService = InstalledCoreService(this)
        launchManager = LaunchManager(this, settings, platformResolver, retroArchLauncher, emuLauncher, apkLauncher, installedCoreService)

        collectionManager = CollectionManager(root)
        recentlyPlayedManager = RecentlyPlayedManager(root)
        orderingManager = OrderingManager(root)
        val romDir = settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) }
        scanner = FileScanner(root, platformResolver, collectionManager, assets, romDir)
        scanner.loadIgnoreExtensions()
        scanner.loadIgnoreFiles()
        collectionManager.migrateCollectionsToHashedNames()
        launchManager.syncRetroArchAssets(root)
        launchManager.syncRetroArchConfig(root)

        ioScope.launch { scanner.ensureDirectories() }

        finishInitializeApp()
    }

    private fun finishInitializeApp() {
        if (screenStack.firstOrNull() !is LauncherScreen.SystemList) {
            screenStack.clear()
            screenStack.add(LauncherScreen.SystemList)
        }

        val root = File(settings.sdCardRoot)

        ioScope.launch {
            installedCoreService.queryAllPackages()
            platformResolver.purgeStaleRaMappings(installedCoreService.installedCores)
        }

        systemListViewModel = SystemListViewModel(scanner, collectionManager, orderingManager, recentlyPlayedManager)
        gameListViewModel = GameListViewModel(scanner, collectionManager, orderingManager, recentlyPlayedManager, platformResolver, resources)
        controllerManager = dev.cannoli.scorza.input.ControllerManager()
        controllerManager.loadBlacklist(this)
        controllerManager.initialize()
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager)
            .registerInputDeviceListener(controllerManager, android.os.Handler(android.os.Looper.getMainLooper()))
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        settingsViewModel.reinitialize(root, packageManager, packageName)
        updateManager = dev.cannoli.scorza.updater.UpdateManager(this, settings)

        ioScope.launch {
            updateManager.updateAvailable.collect { info ->
                settingsViewModel.updateInfo = info
            }
        }

        if (updateManager.shouldAutoCheck()) {
            ioScope.launch { updateManager.checkForUpdate() }
        }

        atomicRename = AtomicRename(root)

        globalOverrides = GlobalOverridesManager { settings.sdCardRoot }
        profileManager.reinitialize(settings.sdCardRoot)

        rescanSystemList()

        val quickResume = File(root, "Config/State/quick_resume.txt")
        if (quickResume.exists()) {
            val lines = try { quickResume.readLines() } catch (_: Exception) { emptyList() }
            quickResume.delete()
            if (lines.size >= 2) {
                val romFile = File(lines[0])
                val tag = lines[1]
                if (romFile.exists()) {
                    val game = Game(romFile, romFile.nameWithoutExtension, tag)
                    launchManager.resumeGame(game)
                    ioScope.launch { recentlyPlayedManager.record(romFile.absolutePath) }
                }
            }
        }

    }

    private fun wrapIndex(current: Int, delta: Int, size: Int): Int =
        if (size == 0) 0 else (current + delta).mod(size)

    private fun wireInput() {
        inputHandler.onUp = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu,
                is DialogState.BulkContextMenu -> {
                    ds.withMenuDelta(-1)?.let { dialogState.value = it }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow <= 0) rows.lastIndex else ks.keyRow - 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                    val newRow = if (ds.selectedRow <= 0) totalRows - 1 else ds.selectedRow - 1
                    dialogState.value = ds.copy(selectedRow = newRow)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                    val newRow = if (curRow <= 0) totalRows - 1 else curRow - 1
                    val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                    dialogState.value = ds.copy(selectedIndex = newIdx)
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
                        else systemListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
                        else gameListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(-1)
                    is LauncherScreen.CoreMapping -> if (screen.mappings.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.mappings.size))
                    is LauncherScreen.CorePicker -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.cores.size))
                    is LauncherScreen.AppPicker -> if (screen.apps.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.apps.size))
                    is LauncherScreen.ColorList -> if (screen.colors.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.colors.size))
                    is LauncherScreen.CollectionPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.collections.size))
                    is LauncherScreen.ChildPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.collections.size))
                    is LauncherScreen.ProfileList -> if (screen.profiles.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.profiles.size))
                    is LauncherScreen.ControlBinding ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, controlButtonCount))
                    is LauncherScreen.ShortcutBinding -> if (!screen.listening)
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, ShortcutAction.entries.size))
                    is LauncherScreen.Credits ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, CREDITS.size))
                    is LauncherScreen.InstalledCores -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.cores.size))
                    is LauncherScreen.DirectoryBrowser -> {
                        val hasSelect = screen.currentPath != "/storage/"
                        val count = screen.entries.size + if (hasSelect) 1 else 0
                        if (count > 0) screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, count))
                    }
                    is LauncherScreen.Setup -> {
                        val maxIndex = if (screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom") 1 else 0
                        screenStack[screenStack.lastIndex] = screen.copy(
                            selectedIndex = (screen.selectedIndex - 1).coerceAtLeast(0)
                        )
                    }
                    is LauncherScreen.InputTester -> {}
                    is LauncherScreen.Installing -> {}
                }
                else -> {}
            }
        }

        inputHandler.onDown = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu,
                is DialogState.BulkContextMenu -> {
                    ds.withMenuDelta(1)?.let { dialogState.value = it }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow >= rows.lastIndex) 0 else ks.keyRow + 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                    val newRow = if (ds.selectedRow >= totalRows - 1) 0 else ds.selectedRow + 1
                    dialogState.value = ds.copy(selectedRow = newRow)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                    val newRow = if (curRow >= totalRows - 1) 0 else curRow + 1
                    val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                    dialogState.value = ds.copy(selectedIndex = newIdx)
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
                        else systemListViewModel.moveSelection(1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
                        else gameListViewModel.moveSelection(1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(1)
                    is LauncherScreen.CoreMapping -> if (screen.mappings.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.mappings.size))
                    is LauncherScreen.CorePicker -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.cores.size))
                    is LauncherScreen.AppPicker -> if (screen.apps.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.apps.size))
                    is LauncherScreen.ColorList -> if (screen.colors.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.colors.size))
                    is LauncherScreen.CollectionPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.collections.size))
                    is LauncherScreen.ChildPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.collections.size))
                    is LauncherScreen.ProfileList -> if (screen.profiles.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.profiles.size))
                    is LauncherScreen.ControlBinding ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, controlButtonCount))
                    is LauncherScreen.ShortcutBinding -> if (!screen.listening)
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, ShortcutAction.entries.size))
                    is LauncherScreen.Credits ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, CREDITS.size))
                    is LauncherScreen.InstalledCores -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.cores.size))
                    is LauncherScreen.DirectoryBrowser -> {
                        val hasSelect = screen.currentPath != "/storage/"
                        val count = screen.entries.size + if (hasSelect) 1 else 0
                        if (count > 0) screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, count))
                    }
                    is LauncherScreen.Setup -> {
                        val maxIndex = if (screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom") 1 else 0
                        screenStack[screenStack.lastIndex] = screen.copy(
                            selectedIndex = (screen.selectedIndex + 1).coerceAtMost(maxIndex)
                        )
                    }
                    is LauncherScreen.InputTester -> {}
                    is LauncherScreen.Installing -> {}
                }
                else -> {}
            }
        }

        inputHandler.onLeft = {
            when (val ds = dialogState.value) {
                is DialogState.Kitchen -> {
                    if (ds.urls.size > 1) {
                        val newIdx = (ds.selectedIndex - 1 + ds.urls.size) % ds.urls.size
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ks.keyCol <= 0) rowSize - 1 else ks.keyCol - 1
                    dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val newCol = if (ds.selectedCol <= 0) COLOR_GRID_COLS - 1 else ds.selectedCol - 1
                    dialogState.value = ds.copy(selectedCol = newCol)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col <= 0) rowSize - 1 else col - 1
                    dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) pageJump(-1)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) pageJump(-1)
                    LauncherScreen.Settings -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.cycleSelected(-1)
                            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
                                ioScope.launch { updateManager.checkForUpdate() }
                            }
                        } else pageJump(-1)
                    }
                    is LauncherScreen.Setup -> {
                        if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                            screenStack[screenStack.lastIndex] = screen.copy(
                                volumeIndex = (screen.volumeIndex - 1 + screen.volumes.size) % screen.volumes.size,
                                customPath = null
                            )
                        }
                    }
                    else -> pageJump(-1)
                }
                else -> {}
            }
        }

        inputHandler.onRight = {
            when (val ds = dialogState.value) {
                is DialogState.Kitchen -> {
                    if (ds.urls.size > 1) {
                        val newIdx = (ds.selectedIndex + 1) % ds.urls.size
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ks.keyCol >= rowSize - 1) 0 else ks.keyCol + 1
                    dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val newCol = if (ds.selectedCol >= COLOR_GRID_COLS - 1) 0 else ds.selectedCol + 1
                    dialogState.value = ds.copy(selectedCol = newCol)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col >= rowSize - 1) 0 else col + 1
                    dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) pageJump(1)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) pageJump(1)
                    LauncherScreen.Settings -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.cycleSelected(1)
                            if (settingsViewModel.getSelectedItem()?.key == "release_channel") {
                                ioScope.launch { updateManager.checkForUpdate() }
                            }
                        } else pageJump(1)
                    }
                    is LauncherScreen.Setup -> {
                        if (screen.selectedIndex == 0 && screen.volumes.size > 1) {
                            screenStack[screenStack.lastIndex] = screen.copy(
                                volumeIndex = (screen.volumeIndex + 1) % screen.volumes.size,
                                customPath = null
                            )
                        }
                    }
                    else -> pageJump(1)
                }
                else -> {}
            }
        }

        inputHandler.onConfirm = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu -> onContextMenuConfirm(ds)
                is DialogState.BulkContextMenu -> onBulkContextMenuConfirm(ds)
                is DialogState.DeleteConfirm -> onDeleteConfirm(ds)
                is DialogState.RenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onRenameConfirm(ds) }
                )
                is DialogState.NewCollectionInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onNewCollectionConfirm(ds) }
                )
                is DialogState.CollectionRenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onCollectionRenameConfirm(ds) }
                )
                is DialogState.ProfileNameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onProfileNameConfirm(ds) }
                )
                is DialogState.NewFolderInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onNewFolderConfirm(ds) }
                )
                is DialogState.QuitConfirm -> {
                    finishAffinity()
                }
                is DialogState.DeleteProfileConfirm -> {
                    profileManager.deleteProfile(ds.profileName)
                    val updated = profileManager.listProfiles()
                    val screen = currentScreen as? LauncherScreen.ProfileList
                    if (screen != null) {
                        screenStack[screenStack.lastIndex] = screen.copy(
                            profiles = updated,
                            selectedIndex = screen.selectedIndex.coerceAtMost(updated.lastIndex)
                        )
                    }
                    dialogState.value = DialogState.None
                }
                is DialogState.ColorPicker -> {
                    val idx = ds.selectedRow * COLOR_GRID_COLS + ds.selectedCol
                    val preset = COLOR_PRESETS.getOrNull(idx)
                    if (preset != null) {
                        val hex = "#%06X".format(preset.color and 0xFFFFFF)
                        settingsViewModel.setColor(ds.settingKey, hex)
                        val entries = settingsViewModel.getColorEntries()
                        updateColorListOnStack(ds.settingKey, entries)
                        dialogState.value = DialogState.None
                    }
                }
                is DialogState.HexColorInput -> {
                    val key = HEX_KEYS.getOrNull(ds.selectedIndex) ?: ""
                    when (key) {
                        "" -> {}
                        "←" -> {
                            if (ds.currentHex.isNotEmpty()) {
                                dialogState.value = ds.copy(currentHex = ds.currentHex.dropLast(1))
                            }
                        }
                        "↵" -> {
                            if (ds.currentHex.length == 6) {
                                settingsViewModel.setColor(ds.settingKey, "#${ds.currentHex}")
                                val entries = settingsViewModel.getColorEntries()
                                updateColorListOnStack(ds.settingKey, entries)
                                dialogState.value = DialogState.None
                            }
                        }
                        else -> {
                            if (ds.currentHex.length < 6) {
                                dialogState.value = ds.copy(currentHex = ds.currentHex + key)
                            }
                        }
                    }
                }
                is DialogState.MissingApp -> {
                    val glState = gameListViewModel.state.value
                    if (glState.platformTag == "tools" || glState.platformTag == "ports") {
                        val game = gameListViewModel.getSelectedGame()
                        if (game != null) {
                            dialogState.value = DialogState.None
                            ioScope.launch {
                                scanner.removeApkLaunch(glState.platformTag, game.displayName)
                                gameListViewModel.reload()
                                rescanSystemList()
                            }
                        }
                    }
                }
                is DialogState.DeleteCollectionConfirm -> {
                    val stem = ds.collectionStem
                    val glState = gameListViewModel.state.value
                    val deletingFromParent = glState.isCollection && !glState.isCollectionsList
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                    if (!deletingFromParent) gameListViewModel.saveCollectionsPosition()
                    ioScope.launch {
                        collectionManager.deleteCollection(stem)
                        if (deletingFromParent) {
                            gameListViewModel.reload()
                            rescanSystemList()
                        } else {
                            if (settings.contentMode == ContentMode.COLLECTIONS) {
                                withContext(Dispatchers.Main) {
                                    screenStack.removeAt(screenStack.lastIndex)
                                    rescanSystemList()
                                }
                            } else {
                                val remaining = collectionManager.scanCollections()
                                    .filter { !it.stem.equals("Favorites", ignoreCase = true) }
                                if (remaining.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        screenStack.removeAt(screenStack.lastIndex)
                                        rescanSystemList()
                                    }
                                } else {
                                    gameListViewModel.loadCollectionsList(restoreIndex = true)
                                }
                            }
                        }
                    }
                }
                is DialogState.UpdateDownload -> {
                    val info = updateManager.updateAvailable.value
                    if (info != null) {
                        updateManager.clearError()
                        ioScope.launch { updateManager.downloadAndInstall(info) }
                    }
                }
                is DialogState.RestartRequired -> {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
                        startActivity(intent, opts)
                        Runtime.getRuntime().exit(0)
                    }
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
                        else onSystemListConfirm()
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isMultiSelectMode()) gameListViewModel.toggleChecked()
                        else if (gameListViewModel.isReorderMode()) gameListViewModel.confirmReorder()
                        else onGameListConfirm()
                    }
                    LauncherScreen.Settings -> {
                        if (!settingsViewModel.state.value.inSubList) {
                            val cat = settingsViewModel.state.value.categories.getOrNull(settingsViewModel.state.value.categoryIndex)
                            if (cat?.key == "about") {
                                dialogState.value = DialogState.About()
                            } else if (cat?.key == "retroachievements" && settings.raToken.isNotEmpty()) {
                                dialogState.value = DialogState.RAAccount(username = settings.raUsername)
                            } else if (cat?.key == "kitchen") {
                                val root = File(settings.sdCardRoot)
                                val km = dev.cannoli.scorza.server.KitchenManager
                                if (!km.isRunning) km.toggle(root, assets, settings.kitchenCodeBypass)
                                else km.setCodeBypass(settings.kitchenCodeBypass)
                                dialogState.value = DialogState.Kitchen(
                                    urls = km.getUrls(hasActiveVpn()),
                                    pin = km.pin,
                                    requirePin = !settings.kitchenCodeBypass
                                )
                            } else {
                                settingsViewModel.enterCategory()
                            }
                        } else {
                            when (val key = settingsViewModel.enterSelected()) {
                                "status_bar" -> settingsViewModel.enterSubCategory("status_bar", R.string.settings_status_bar)
                                "sd_root" -> pushDirectoryBrowser(BrowsePurpose.SD_ROOT, settings.sdCardRoot)
                                "rom_directory" -> {
                                    val startPath = settings.romDirectory.ifEmpty { settings.sdCardRoot }
                                    pushDirectoryBrowser(BrowsePurpose.ROM_DIRECTORY, startPath)
                                }
                                "colors" -> screenStack.add(LauncherScreen.ColorList(
                                    colors = settingsViewModel.getColorEntries()
                                ))
                                "profiles" -> pushScreen(LauncherScreen.ProfileList(profiles = profileManager.listProfiles()))
                                "shortcuts" -> pushScreen(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
                                "input_tester" -> {
                                    inputTesterViewModel.reset()
                                    initTesterProfiles()
                                    refreshInputTesterPorts()
                                    pushScreen(LauncherScreen.InputTester)
                                }
                                "core_mapping" -> {
                                    val initial = platformResolver.getDetailedMappings(packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(this@MainActivity), installedCoreService.unresponsivePackages)
                                    screenStack.add(LauncherScreen.CoreMapping(mappings = initial, allMappings = initial))
                                    ioScope.launch {
                                        installedCoreService.queryAllPackages()
                                        withContext(Dispatchers.Main) {
                                            val cm = screenStack.lastOrNull() as? LauncherScreen.CoreMapping ?: return@withContext
                                            val all = platformResolver.getDetailedMappings(packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(this@MainActivity), installedCoreService.unresponsivePackages)
                                            screenStack[screenStack.lastIndex] = cm.copy(mappings = filterCoreMappings(all, cm.filter), allMappings = all)
                                        }
                                    }
                                }
                                "set_default_launcher" -> startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
                                "installed_cores" -> queryInstalledCores()
                                "rebuild_cache" -> {
                                    scanner.invalidateAllCaches()
                                    rescanSystemList()
                                }
                                "manage_tools" -> openAppPicker("tools")
                                "manage_ports" -> openAppPicker("ports")
                                "ra_username" -> {
                                    val current = settings.raUsername
                                    dialogState.value = DialogState.RenameInput(
                                        gameName = "ra_username",
                                        currentName = current,
                                        cursorPos = current.length
                                    )
                                }
                                "ra_password" -> {
                                    dialogState.value = DialogState.RenameInput(
                                        gameName = "ra_password",
                                        currentName = settingsViewModel.raPassword,
                                        cursorPos = settingsViewModel.raPassword.length
                                    )
                                }
                                "ra_login" -> {
                                    val passwordForLogin = settingsViewModel.raPassword
                                    val ra = RetroAchievementsManager(
                                        onLogin = { success, nameOrError, token ->
                                            if (success && token != null) {
                                                settings.raUsername = nameOrError
                                                settings.raToken = token
                                                settings.raPassword = passwordForLogin
                                                settingsViewModel.raPassword = ""
                                                dialogState.value = DialogState.RAAccount(username = nameOrError)
                                            } else {
                                                dialogState.value = DialogState.RALoggingIn(message = "Invalid username or password")
                                            }
                                            loginPollHandler.removeCallbacks(loginPollRunnable)
                                            loginManager?.destroy()
                                            loginManager = null
                                        }
                                    )
                                    ra.init()
                                    ra.loginWithPassword(settings.raUsername, settingsViewModel.raPassword)
                                    loginManager = ra
                                    loginPollHandler.postDelayed(loginPollRunnable, 100)
                                    dialogState.value = DialogState.RALoggingIn()
                                }
                                null -> {}
                                else -> {
                                    if (key.startsWith("color_")) {
                                        val entries = settingsViewModel.getColorEntries()
                                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                                        screenStack.add(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                                        openColorPicker(key)
                                    } else {
                                        val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                                        dialogState.value = DialogState.RenameInput(
                                            gameName = key,
                                            currentName = displayValue,
                                            cursorPos = displayValue.length
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is LauncherScreen.CoreMapping -> {
                        screen.mappings.getOrNull(screen.selectedIndex)?.let { entry ->
                            if (entry.coreDisplayName == "Missing" || entry.coreDisplayName == "None") return@let
                            val bundledCoresDir = LaunchManager.extractBundledCores(this@MainActivity)
                            val options = platformResolver.getCorePickerOptions(
                                entry.tag, packageManager,
                                installedRaCores = installedCoreService.installedCores,
                                embeddedCoresDir = bundledCoresDir,
                                unresponsivePackages = installedCoreService.unresponsivePackages
                            )
                            val currentCore = platformResolver.getCoreMapping(entry.tag)
                            val currentApp = platformResolver.getAppPackage(entry.tag)
                            val currentRunner = entry.runnerLabel
                            val selectedIdx = if ((currentRunner == "App" || currentRunner == "Standalone") && currentApp != null) {
                                options.indexOfFirst { it.appPackage == currentApp }.coerceAtLeast(0)
                            } else {
                                options.indexOfFirst { it.coreId == currentCore && it.runnerLabel == currentRunner }
                                    .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                            }
                            pushScreen(LauncherScreen.CorePicker(
                                tag = entry.tag,
                                platformName = entry.platformName,
                                cores = options,
                                selectedIndex = selectedIdx,
                                activeIndex = selectedIdx
                            ))
                        }
                    }
                    is LauncherScreen.CorePicker -> onCorePickerConfirm(screen)
                    is LauncherScreen.ColorList -> {
                        screen.colors.getOrNull(screen.selectedIndex)?.let { entry ->
                            openColorPicker(entry.key)
                        }
                    }
                    is LauncherScreen.CollectionPicker -> {
                        if (screen.collections.isNotEmpty()) {
                            val newChecked = if (screen.selectedIndex in screen.checkedIndices) {
                                screen.checkedIndices - screen.selectedIndex
                            } else {
                                screen.checkedIndices + screen.selectedIndex
                            }
                            screenStack[screenStack.lastIndex] = screen.copy(checkedIndices = newChecked)
                        }
                    }
                    is LauncherScreen.ChildPicker -> {
                        if (screen.collections.isNotEmpty()) {
                            val newChecked = if (screen.selectedIndex in screen.checkedIndices) {
                                screen.checkedIndices - screen.selectedIndex
                            } else {
                                screen.checkedIndices + screen.selectedIndex
                            }
                            screenStack[screenStack.lastIndex] = screen.copy(checkedIndices = newChecked)
                        }
                    }
                    is LauncherScreen.AppPicker -> {
                        val newChecked = if (screen.selectedIndex in screen.checkedIndices) {
                            screen.checkedIndices - screen.selectedIndex
                        } else {
                            screen.checkedIndices + screen.selectedIndex
                        }
                        screenStack[screenStack.lastIndex] = screen.copy(checkedIndices = newChecked)
                    }
                    is LauncherScreen.ProfileList -> {
                        val name = screen.profiles.getOrNull(screen.selectedIndex)
                        if (name != null) {
                            pushScreen(LauncherScreen.ControlBinding(
                                controls = profileManager.readControls(name),
                                profileName = name
                            ))
                        }
                    }
                    is LauncherScreen.ControlBinding -> {
                        if (screen.listeningIndex < 0) {
                            screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = screen.selectedIndex, listenCountdownMs = 0)
                            shortcutCountdownHandler.postDelayed(controlListenRunnable, controlListenTickMs)
                        }
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            screenStack[screenStack.lastIndex] = screen.copy(
                                listening = true, heldKeys = emptySet(), countdownMs = 0
                            )
                        }
                    }
                    is LauncherScreen.Setup -> {
                        val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                        val folderIndex = if (isCustom) 1 else -1
                        if (screen.selectedIndex == folderIndex) {
                            pushDirectoryBrowser(BrowsePurpose.SETUP, "/storage/")
                        }
                    }
                    is LauncherScreen.Installing -> {
                        if (screen.finished) {
                            settings.sdCardRoot = screen.targetPath
                            settings.setupCompleted = true
                            initializeApp()
                        }
                    }
                    is LauncherScreen.InputTester -> {}
                    is LauncherScreen.Credits -> {}
                    is LauncherScreen.InstalledCores -> {}
                    is LauncherScreen.DirectoryBrowser -> {
                        val hasSelect = screen.currentPath != "/storage/"
                        val selectIndex = if (hasSelect) 0 else -1
                        if (screen.selectedIndex == selectIndex) {
                            onDirectoryBrowserResult(screen.purpose, screen.currentPath)
                            screenStack.removeAt(screenStack.lastIndex)
                        } else {
                            val entryIdx = screen.selectedIndex - if (hasSelect) 1 else 0
                            val folderName = screen.entries[entryIdx]
                            val newPath = resolveDirectoryEntry(screen.currentPath, folderName)
                            val newEntries = listDirectories(newPath)
                            screenStack[screenStack.lastIndex] = LauncherScreen.DirectoryBrowser(
                                purpose = screen.purpose,
                                currentPath = newPath,
                                entries = newEntries
                            )
                        }
                    }
                }
                else -> {}
            }
        }

        inputHandler.onBack = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    ds.withBackspace()?.let { dialogState.value = it }
                }
                is DialogState.ColorPicker -> {
                    val entries = settingsViewModel.getColorEntries()
                    updateColorListOnStack(ds.settingKey, entries)
                    dialogState.value = DialogState.None
                }
                is DialogState.HexColorInput -> {
                    openColorPicker(ds.settingKey)
                }
                is DialogState.ContextMenu, is DialogState.BulkContextMenu -> {
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                }
                is DialogState.DeleteConfirm,
                is DialogState.DeleteCollectionConfirm -> {
                    restoreContextMenu()
                }
                is DialogState.DeleteProfileConfirm,
                is DialogState.QuitConfirm -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.CollectionCreated -> {
                    refreshCollectionPickerOnStack()
                    dialogState.value = DialogState.None
                }
                is DialogState.RenameResult -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.MissingCore,
                is DialogState.MissingApp,
                is DialogState.LaunchError -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.UpdateDownload -> {
                    updateManager.cancelDownload()
                    updateManager.clearError()
                    dialogState.value = DialogState.About()
                }
                is DialogState.About,
                is DialogState.Kitchen -> {
                    dialogState.value = DialogState.None
                    rescanSystemList()
                }
                is DialogState.RAAccount -> {
                    dialogState.value = DialogState.None
                    if (settingsViewModel.state.value.inSubList) settingsViewModel.exitSubList()
                }
                is DialogState.RALoggingIn -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.RestartRequired -> {}
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.cancelReorder(showRecentlyPlayed = settings.showRecentlyPlayed, showEmpty = settings.showEmpty, contentMode = settings.contentMode, toolsName = settings.toolsName, portsName = settings.portsName)
                        else if (settings.mainMenuQuit) dialogState.value = DialogState.QuitConfirm
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isMultiSelectMode()) {
                            gameListViewModel.cancelMultiSelect()
                        } else if (gameListViewModel.isReorderMode()) {
                            gameListViewModel.cancelReorder()
                        } else if (!navigating) {
                            val glState = gameListViewModel.state.value
                            if (!gameListViewModel.exitSubfolder()) {
                                if (gameListViewModel.exitChildCollection { scanResumableGames() }) {
                                    // navigated back to parent collection
                                } else if (settings.contentMode == ContentMode.PLATFORMS
                                    && glState.isCollection && glState.collectionName != null
                                    && !glState.collectionName.equals("Favorites", ignoreCase = true)) {
                                    gameListViewModel.loadCollectionsList(restoreIndex = true)
                                } else {
                                    screenStack.removeAt(screenStack.lastIndex)
                                    rescanSystemList()
                                }
                            }
                        }
                    }
                    LauncherScreen.Settings -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.save()
                            settingsViewModel.exitSubList()
                            rescanSystemList()
                        } else {
                            settingsViewModel.cancel()
                            screenStack.removeAt(screenStack.lastIndex)
                        }
                    }
                    is LauncherScreen.CorePicker -> {
                        screenStack.removeAt(screenStack.lastIndex)
                        if (screen.gamePath != null) {
                            restoreContextMenu()
                        } else {
                            val cm = screenStack.lastOrNull()
                            if (cm is LauncherScreen.CoreMapping) {
                                val all = platformResolver.getDetailedMappings(packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(this@MainActivity), installedCoreService.unresponsivePackages)
                                val filtered = filterCoreMappings(all, cm.filter)
                                val idx = filtered.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                                screenStack[screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                            }
                        }
                    }
                    is LauncherScreen.CoreMapping -> {
                        platformResolver.saveCoreMappings()
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.AppPicker -> {
                        onAppPickerConfirm(screen)
                    }
                    is LauncherScreen.ColorList -> {
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.CollectionPicker -> {
                        onCollectionPickerConfirm(screen)
                    }
                    is LauncherScreen.ChildPicker -> {
                        onChildPickerConfirm(screen)
                    }
                    is LauncherScreen.ProfileList -> {
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.ControlBinding -> {
                        profileManager.saveControls(screen.profileName, screen.controls)
                        screenStack.removeAt(screenStack.lastIndex)
                        val prev = screenStack.lastOrNull()
                        if (prev is LauncherScreen.ProfileList) {
                            screenStack[screenStack.lastIndex] = prev.copy(profiles = profileManager.listProfiles())
                        }
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        cancelShortcutListening()
                        globalOverrides.saveShortcuts(screen.shortcuts)
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.Credits -> {
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.InstalledCores -> {
                        unregisterCoreQueryReceiver()
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.DirectoryBrowser -> {
                        val parent = parentDirectory(screen.currentPath)
                        if (parent != null) {
                            val newEntries = listDirectories(parent)
                            screenStack[screenStack.lastIndex] = LauncherScreen.DirectoryBrowser(
                                purpose = screen.purpose,
                                currentPath = parent,
                                entries = newEntries
                            )
                        }
                    }
                    is LauncherScreen.Setup -> finishAffinity()
                    is LauncherScreen.InputTester -> {}
                    is LauncherScreen.Installing -> {}
                }
            }
        }

        inputHandler.onStart = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> onRenameConfirm(ds)
                is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
                is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
                is DialogState.ProfileNameInput -> onProfileNameConfirm(ds)
                is DialogState.NewFolderInput -> onNewFolderConfirm(ds)
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            onSystemListContextMenu()
                        }
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isMultiSelectMode()) {
                            val glState = gameListViewModel.state.value
                            val checkedGames = glState.checkedIndices
                                .mapNotNull { glState.games.getOrNull(it) }
                                .filter { !it.isSubfolder }
                            if (checkedGames.isNotEmpty()) {
                                val paths = checkedGames.map { it.file.absolutePath }
                                val favPaths = collectionManager.getFavoritePaths()
                                val allFav = paths.all { it in favPaths }
                                val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
                                val options = mutableListOf<String>()
                                if (glState.platformTag == "recently_played") options.add(MENU_REMOVE_FROM_RECENTS)
                                options.add(if (allFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                                if (glState.isCollection && glState.collectionName != null) {
                                    options.add(MENU_REMOVE_FROM_COLLECTION)
                                }
                                if (isApkList) {
                                    options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_REMOVE))
                                } else {
                                    options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_DELETE_ART, MENU_DELETE_GAME))
                                }
                                gameListViewModel.confirmMultiSelect()
                                dialogState.value = DialogState.BulkContextMenu(
                                    gamePaths = paths,
                                    options = options
                                )
                            } else {
                                gameListViewModel.cancelMultiSelect()
                            }
                        } else if (gameListViewModel.isReorderMode()) {
                            gameListViewModel.confirmReorder()
                        } else {
                        val glState = gameListViewModel.state.value
                        val game = gameListViewModel.getSelectedGame()
                        if (game != null) {
                            val menuName = game.displayName.removePrefix("★ ").let { if (game.isChildCollection) it.removePrefix("/") else it }
                            dialogState.value = DialogState.ContextMenu(
                                gameName = menuName,
                                options = buildGameContextOptions(game, glState)
                            )
                        }
                        }
                    }
                    is LauncherScreen.Setup -> {
                        val isCustom = screen.volumes.getOrNull(screen.volumeIndex)?.first == "Custom"
                        val continueEnabled = !isCustom || screen.customPath != null
                        if (continueEnabled) {
                            val targetPath = if (isCustom) screen.customPath!! else screen.volumes[screen.volumeIndex].second + "Cannoli/"
                            screenStack[screenStack.lastIndex] = LauncherScreen.Installing(targetPath = targetPath)
                            startInstalling(targetPath)
                        }
                    }
                    is LauncherScreen.ProfileList -> {
                        val pl = currentScreen as LauncherScreen.ProfileList
                        val name = pl.profiles.getOrNull(pl.selectedIndex)
                        if (name != null && !dev.cannoli.scorza.input.ProfileManager.isProtected(name)) {
                            dialogState.value = DialogState.ContextMenu(
                                gameName = name,
                                options = listOf("Rename", "Delete")
                            )
                        }
                    }
                    else -> {}
                }
                else -> {}
            }
        }

        inputHandler.onSelect = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    if (!selectDown) {
                        selectDown = true
                        selectHeld = false
                        selectHoldHandler.postDelayed(selectHoldRunnable, 400)
                    }
                }
                DialogState.None -> if (!selectDown) {
                    selectDown = true
                    selectHeld = false
                    selectHandled = false
                    when (currentScreen) {
                        LauncherScreen.SystemList -> {
                            if (systemListViewModel.isReorderMode()) {
                                systemListViewModel.confirmReorder()
                            } else {
                                systemListViewModel.enterReorderMode()
                            }
                        }
                        LauncherScreen.GameList -> {
                            val glState = gameListViewModel.state.value
                            val isApkList = glState.platformTag == "tools" || glState.platformTag == "ports"
                            if (glState.isCollection && !glState.isCollectionsList && glState.subfolderPath == null) {
                                if (gameListViewModel.isReorderMode()) {
                                    gameListViewModel.confirmReorder()
                                    selectHandled = true
                                } else if (gameListViewModel.isMultiSelectMode()) {
                                    gameListViewModel.confirmMultiSelect()
                                    selectHandled = true
                                } else {
                                    selectHoldHandler.postDelayed(collectionSelectHoldRunnable, 400)
                                }
                            } else if (isApkList) {
                                if (gameListViewModel.isReorderMode()) {
                                    gameListViewModel.confirmReorder()
                                    selectHandled = true
                                } else if (gameListViewModel.isMultiSelectMode()) {
                                    gameListViewModel.confirmMultiSelect()
                                    selectHandled = true
                                } else {
                                    selectHoldHandler.postDelayed(collectionSelectHoldRunnable, 400)
                                }
                            } else if (glState.isCollectionsList) {
                                if (gameListViewModel.isReorderMode()) {
                                    gameListViewModel.confirmReorder()
                                } else {
                                    gameListViewModel.enterReorderMode()
                                }
                            } else if (glState.subfolderPath == null) {
                                if (gameListViewModel.isMultiSelectMode()) {
                                    gameListViewModel.confirmMultiSelect()
                                } else {
                                    gameListViewModel.enterMultiSelect()
                                }
                            }
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        inputHandler.onNorth = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    ds.withInsertedChar(" ")?.let { dialogState.value = it }
                }
                is DialogState.About -> {
                    dialogState.value = DialogState.None
                    screenStack.add(LauncherScreen.Credits())
                }
                is DialogState.Kitchen -> {
                    dev.cannoli.scorza.server.KitchenManager.stop()
                    dialogState.value = DialogState.None
                    rescanSystemList()
                }
                is DialogState.RAAccount -> {
                    settings.raUsername = ""
                    settings.raToken = ""
                    settings.raPassword = ""
                    settingsViewModel.load()
                    dialogState.value = DialogState.None
                }
                is DialogState.ColorPicker -> {
                    val currentHex = settingsViewModel.getColorHex(ds.settingKey).removePrefix("#")
                    dialogState.value = DialogState.HexColorInput(
                        settingKey = ds.settingKey,
                        title = ds.title,
                        currentHex = currentHex
                    )
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        val fgh = settings.contentMode == ContentMode.FIVE_GAME_HANDHELD
                        val item = systemListViewModel.getSelectedItem()
                        if (fgh && item is SystemListViewModel.ListItem.GameItem) {
                            val game = item.game
                            val isResumable = resumableGames.contains(game.file.absolutePath)
                            if (isResumable && settings.swapPlayResume) {
                                val errorDialog = launchManager.launchGame(game)
                                if (errorDialog != null) {
                                    dialogState.value = errorDialog
                                } else {
                                    ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                                }
                            } else if (isResumable) {
                                launchManager.resumeGame(game)
                                ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                            }
                        } else if (!fgh) {
                            systemListViewModel.savePosition()
                            settingsViewModel.load()
                            screenStack.add(LauncherScreen.Settings)
                            if (updateManager.isOnline()) {
                                ioScope.launch { updateManager.checkForUpdate() }
                            }
                        }
                    }
                    LauncherScreen.Settings -> {
                        val item = settingsViewModel.getSelectedItem()
                        if (item?.key == "rom_directory" && settings.romDirectory.isNotEmpty()) {
                            settingsViewModel.clearRomDirectory()
                            scanner.invalidateAllCaches()
                            dialogState.value = DialogState.RestartRequired
                        }
                    }
                    LauncherScreen.GameList -> {
                        val glState = gameListViewModel.state.value
                        if (!glState.isCollectionsList) {
                            val game = gameListViewModel.getSelectedGame()
                            if (game != null && !game.isSubfolder && !game.isChildCollection) {
                                val isResumable = resumableGames.contains(game.file.absolutePath)
                                val trackRecent = glState.platformTag != "tools"
                                if (isResumable && settings.swapPlayResume) {
                                    val errorDialog = launchManager.launchGame(game)
                                    if (errorDialog != null) {
                                        dialogState.value = errorDialog
                                    } else if (trackRecent) {
                                        ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                                    }
                                } else if (isResumable) {
                                    launchManager.resumeGame(game)
                                    if (trackRecent) ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                                }
                            }
                        }
                    }
                    is LauncherScreen.ProfileList -> {
                        dialogState.value = DialogState.ProfileNameInput(isNew = true)
                    }
                    is LauncherScreen.ControlBinding -> {
                        if (screen.listeningIndex < 0) {
                            val btn = controlButtons.getOrNull(screen.selectedIndex)
                            if (btn != null && btn.prefKey != "btn_menu") {
                                val currentKeyCode = screen.controls[btn.prefKey] ?: btn.defaultKeyCode
                                if (currentKeyCode != LibretroInput.UNMAPPED) {
                                    screenStack[screenStack.lastIndex] = screen.copy(
                                        controls = screen.controls + (btn.prefKey to LibretroInput.UNMAPPED)
                                    )
                                }
                            }
                        }
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            ShortcutAction.entries.getOrNull(screen.selectedIndex)?.let { action ->
                                screenStack[screenStack.lastIndex] = screen.copy(
                                    shortcuts = screen.shortcuts + (action to emptySet())
                                )
                            }
                        }
                    }
                    is LauncherScreen.DirectoryBrowser -> {
                        if (screen.currentPath != "/storage/") {
                            dialogState.value = DialogState.NewFolderInput(parentPath = screen.currentPath)
                        }
                    }
                    else -> {}
                }
                else -> {}
            }
        }

        inputHandler.onWest = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.CollectionRenameInput -> {
                    restoreContextMenu()
                }
                is DialogState.NewCollectionInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.About -> {
                    val info = updateManager.updateAvailable.value
                    if (info != null) {
                        dialogState.value = DialogState.UpdateDownload(info.versionName, info.changelog)
                        ioScope.launch { updateManager.downloadAndInstall(info) }
                    }
                }
                DialogState.None -> {
                    when (currentScreen) {
                        LauncherScreen.SystemList -> {
                            if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                                systemListViewModel.savePosition()
                                settingsViewModel.load()
                                screenStack.add(LauncherScreen.Settings)
                                if (updateManager.isOnline()) {
                                    ioScope.launch { updateManager.checkForUpdate() }
                                }
                            } else {
                                val km = dev.cannoli.scorza.server.KitchenManager
                                if (km.isRunning || systemListViewModel.state.value.items.isEmpty()) {
                                    val root = File(settings.sdCardRoot)
                                    if (!km.isRunning) km.toggle(root, assets, settings.kitchenCodeBypass)
                                    else km.setCodeBypass(settings.kitchenCodeBypass)
                                    dialogState.value = DialogState.Kitchen(
                                        urls = km.getUrls(hasActiveVpn()),
                                        pin = km.pin,
                                        requirePin = !settings.kitchenCodeBypass
                                    )
                                }
                            }
                        }
                        LauncherScreen.GameList -> {
                            val glState = gameListViewModel.state.value
                            if (glState.isCollectionsList) {
                                dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList())
                            } else if (glState.isCollection && glState.collectionName != null) {
                                dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList(), parentStem = glState.collectionName)
                            }
                        }
                        is LauncherScreen.CollectionPicker -> {
                            val screen = currentScreen as LauncherScreen.CollectionPicker
                            dialogState.value = DialogState.NewCollectionInput(gamePaths = screen.gamePaths)
                        }
                        is LauncherScreen.CoreMapping -> {
                            val screen = currentScreen as LauncherScreen.CoreMapping
                            val newFilter = (screen.filter + 1) % 4
                            screenStack[screenStack.lastIndex] = screen.copy(
                                mappings = filterCoreMappings(screen.allMappings, newFilter),
                                filter = newFilter, selectedIndex = 0, scrollTarget = 0
                            )
                        }
                        is LauncherScreen.DirectoryBrowser -> {
                            screenStack.removeAt(screenStack.lastIndex)
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        inputHandler.onL1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    if (ks.cursorPos > 0) dialogState.value = ds.withCursor(ks.cursorPos - 1)
                }
                DialogState.None -> if (currentScreen == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(-1)
                else -> {}
            }
        }

        inputHandler.onR1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    if (ks.cursorPos < ks.currentName.length) dialogState.value = ds.withCursor(ks.cursorPos + 1)
                }
                DialogState.None -> if (currentScreen == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(1)
                else -> {}
            }
        }

        inputHandler.onL2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    dialogState.value = ds.withCursor(0)
                }
                else -> {}
            }
        }

        inputHandler.onR2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput,
                is DialogState.ProfileNameInput,
                is DialogState.NewFolderInput -> {
                    val ks = ds.asKeyboardState()!!
                    dialogState.value = ds.withCursor(ks.currentName.length)
                }
                else -> {}
            }
        }
    }

    private fun getInstalledLauncherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                val label = ri.loadLabel(packageManager).toString()
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
    }

    private fun openAppPicker(type: String) {
        val allApps = getInstalledLauncherApps()
        val dir = if (type == "tools") scanner.tools else scanner.ports
        val existing = scanner.scanApkLaunches(dir).map { it.packageName }.toSet()
        val initialChecked = allApps.indices.filter { allApps[it].second in existing }.toSet()
        val title = if (type == "tools") "Manage Tools" else "Manage Ports"
        screenStack.add(LauncherScreen.AppPicker(
            type = type,
            title = title,
            apps = allApps.map { it.first },
            packages = allApps.map { it.second },
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun onAppPickerConfirm(state: LauncherScreen.AppPicker) {
        val selected = state.checkedIndices.mapNotNull { idx ->
            val name = state.apps.getOrNull(idx) ?: return@mapNotNull null
            val pkg = state.packages.getOrNull(idx) ?: return@mapNotNull null
            name to pkg
        }
        val dir = if (state.type == "tools") scanner.tools else scanner.ports
        ioScope.launch {
            scanner.syncApkLaunches(dir, selected)
            rescanSystemList()
        }
        screenStack.removeAt(screenStack.lastIndex)
    }

    private fun hasActiveVpn(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun rescanSystemList() {
        collectionManager.invalidateFavorites()
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
            ensureFiveGameHandheldCollection()
        }
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            showEmpty = settings.showEmpty,
            contentMode = settings.contentMode,
            toolsName = settings.toolsName,
            portsName = settings.portsName,
            onReady = {
                if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                    scanResumableGames()
                }
            }
        )
    }

    private fun ensureFiveGameHandheldCollection() {
        val stems = collectionManager.getCollectionStems()
        if (collectionManager.findFghStem() == null) {
            collectionManager.createCollection("5GH")
        }
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> R.string.setting_color_accent
            "color_highlight" -> R.string.setting_color_highlight
            "color_highlight_text" -> R.string.setting_color_highlight_text
            "color_text" -> R.string.setting_color_text
            "color_title" -> R.string.setting_color_title
            else -> return ""
        }
        return getString(labelRes)
    }

    private fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    private fun onSystemListContextMenu() {
        val item = systemListViewModel.getSelectedItem() ?: return
        if (item is SystemListViewModel.ListItem.GameItem) {
            val game = item.game
            pendingFghGame = game
            val isFav = game.displayName.startsWith("★")
            val menuName = game.displayName.removePrefix("★ ")
            val options = buildList {
                add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_EMULATOR_OVERRIDE)
                add(MENU_DELETE_GAME)
            }
            dialogState.value = DialogState.ContextMenu(gameName = menuName, options = options)
            return
        }
        val name = when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> item.platform.displayName
            is SystemListViewModel.ListItem.ToolsFolder -> item.name
            is SystemListViewModel.ListItem.PortsFolder -> item.name
            else -> return
        }
        dialogState.value = DialogState.ContextMenu(
            gameName = name,
            options = listOf(MENU_RENAME)
        )
    }

    private fun onFghContextMenuConfirm(game: dev.cannoli.scorza.model.Game, state: DialogState.ContextMenu) {
        val selected = state.options[state.selectedOption]
        when {
            selected == MENU_ADD_FAVORITE || selected == MENU_REMOVE_FAVORITE -> {
                val path = game.file.absolutePath
                ioScope.launch {
                    val isFav = game.displayName.startsWith("★")
                    if (isFav) collectionManager.removeFromCollection("Favorites", path)
                    else collectionManager.addToCollection("Favorites", path)
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(listOf(game.file.absolutePath), game.displayName.removePrefix("★ "))
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                val tag = game.platformTag
                val bundledCoresDir2 = LaunchManager.extractBundledCores(this@MainActivity)
                val options = platformResolver.getCorePickerOptions(tag, packageManager,
                    installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir2,
                    unresponsivePackages = installedCoreService.unresponsivePackages)
                val platformCoreId = platformResolver.getCoreMapping(tag)
                val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
                val defaultLabel = if (platformCoreName.isNotEmpty()) "Platform Setting ($platformCoreName)" else "Platform Setting"
                val defaultOption = CorePickerOption("", defaultLabel, "")
                val allOptions = listOf(defaultOption) + options
                val override = platformResolver.getGameOverride(game.file.absolutePath)
                val selectedIdx = if (override?.appPackage != null) {
                    allOptions.indexOfFirst { it.appPackage == override.appPackage }.coerceAtLeast(0)
                } else if (override != null) {
                    allOptions.indexOfFirst { it.coreId == override.coreId && (it.runnerLabel == override.runner || override.runner == null) }
                        .coerceAtLeast(0)
                } else {
                    0
                }
                dialogState.value = DialogState.None
                screenStack.add(LauncherScreen.CorePicker(
                    tag = tag,
                    platformName = game.displayName.removePrefix("★ "),
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = game.file.absolutePath,
                    activeIndex = selectedIdx
                ))
            }
            selected == MENU_DELETE || selected == MENU_DELETE_GAME -> {
                dialogState.value = DialogState.DeleteConfirm(gameName = game.displayName.removePrefix("★ "))
            }
        }
    }

    private fun onSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.gameName) {
            dialogState.value = DialogState.None
            return
        }
        val item = systemListViewModel.getSelectedItem()
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformResolver.setDisplayName(item.platform.tag, newName)
                    rescanSystemList()
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                ioScope.launch {
                    collectionManager.renameCollection(item.name, newName)
                    rescanSystemList()
                }
            }
            else -> {}
        }
        dialogState.value = DialogState.None
    }

    private fun onSystemListConfirm() {
        if (navigating) return
        systemListViewModel.savePosition()
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                navigating = true
                gameListViewModel.loadRecentlyPlayed {
                    scanResumableGames()
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                navigating = true
                gameListViewModel.loadCollection("Favorites") {
                    scanResumableGames()
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                navigating = true
                gameListViewModel.loadCollectionsList {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                navigating = true
                gameListViewModel.loadPlatform(item.platform.tag, item.platform.allTags) {
                    scanResumableGames()
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                navigating = true
                gameListViewModel.loadCollection(item.name) {
                    scanResumableGames()
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.GameItem -> {
                val game = item.game
                val isResumable = resumableGames.contains(game.file.absolutePath)
                if (isResumable && settings.swapPlayResume) {
                    launchManager.resumeGame(game)
                    ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                } else {
                    val errorDialog = launchManager.launchGame(game)
                    if (errorDialog != null) {
                        dialogState.value = errorDialog
                    } else {
                        ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                    }
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("tools", item.name) {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("ports", item.name) {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            else -> {}
        }
    }

    private fun onGameListConfirm() {
        if (navigating) return
        val game = gameListViewModel.getSelectedGame() ?: return

        if (gameListViewModel.state.value.isCollectionsList) {
            navigating = true
            gameListViewModel.loadCollection(game.file.nameWithoutExtension) {
                scanResumableGames()
                navigating = false
            }
            return
        }

        if (game.isChildCollection) {
            navigating = true
            val childStem = game.file.name
            gameListViewModel.enterChildCollection(childStem) {
                scanResumableGames()
                navigating = false
            }
            return
        }

        if (game.isSubfolder) {
            gameListViewModel.enterSubfolder(game.file.name)
            return
        }

        val isResumable = resumableGames.contains(game.file.absolutePath)
        val tag = gameListViewModel.state.value.platformTag
        val trackRecent = tag != "tools"
        if (isResumable && settings.swapPlayResume) {
            launchManager.resumeGame(game)
            if (trackRecent) {
                ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                if (tag == "recently_played") pendingRecentlyPlayedReorder = true
            }
        } else {
            val errorDialog = launchManager.launchGame(game)
            if (errorDialog != null) {
                dialogState.value = errorDialog
            } else if (trackRecent) {
                ioScope.launch { recentlyPlayedManager.record(game.file.absolutePath) }
                if (tag == "recently_played") pendingRecentlyPlayedReorder = true
            }
        }
    }

    private fun scanResumableGames() {
        val gameListGames = gameListViewModel.state.value.games
        val systemListGames = systemListViewModel.state.value.items
            .filterIsInstance<SystemListViewModel.ListItem.GameItem>()
            .map { it.game }
        val games = (gameListGames + systemListGames).distinctBy { it.file.absolutePath }
        ioScope.launch {
            val result = launchManager.findResumableGames(games)
            withContext(Dispatchers.Main) { resumableGames = result }
        }
    }

    private fun onCorePickerConfirm(screen: LauncherScreen.CorePicker) {
        val chosen = screen.cores.getOrNull(screen.selectedIndex) ?: return
        if (screen.gamePath != null) {
            if (chosen.coreId.isEmpty() && chosen.appPackage == null) {
                platformResolver.setGameOverride(screen.gamePath, null, null)
                platformResolver.setGameAppOverride(screen.gamePath, null)
            } else if (chosen.appPackage != null) {
                platformResolver.setGameAppOverride(screen.gamePath, chosen.appPackage)
            } else {
                platformResolver.setGameOverride(screen.gamePath, chosen.coreId, chosen.runnerLabel, chosen.raPackage)
            }
            screenStack.removeAt(screenStack.lastIndex)
            restoreContextMenu()
        } else {
            if (chosen.appPackage != null) {
                platformResolver.setAppMapping(screen.tag, chosen.appPackage)
            } else {
                platformResolver.setCoreMapping(screen.tag, chosen.coreId, chosen.runnerLabel, chosen.raPackage)
            }
            platformResolver.saveCoreMappings()
            screenStack.removeAt(screenStack.lastIndex)
            val cm = screenStack.lastOrNull()
            if (cm is LauncherScreen.CoreMapping) {
                val all = platformResolver.getDetailedMappings(packageManager, installedCoreService.installedCores, LaunchManager.extractBundledCores(this@MainActivity), installedCoreService.unresponsivePackages)
                val filtered = filterCoreMappings(all, cm.filter)
                val idx = filtered.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                screenStack[screenStack.lastIndex] = cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
            }
        }
    }

    private fun buildGameContextOptions(game: dev.cannoli.scorza.model.Game, glState: dev.cannoli.scorza.ui.viewmodel.GameListViewModel.State): List<String> {
        if (glState.isCollectionsList || game.isChildCollection) return listOf(MENU_RENAME, MENU_CHILD_COLLECTIONS, MENU_DELETE)
        if (game.isSubfolder) return listOf(MENU_RENAME, MENU_DELETE)
        val isApk = game.platformTag == "tools" || game.platformTag == "ports"
        val isFav = game.displayName.startsWith("★") ||
            (glState.isCollection && glState.collectionName == "Favorites")
        return buildList {
            if (glState.platformTag == "recently_played") add(MENU_REMOVE_FROM_RECENTS)
            add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
            if (isApk) {
                add(MENU_MANAGE_COLLECTIONS)
                add(MENU_REMOVE)
            } else {
                addAll(gameContextOptions.map { item ->
                    if (item == MENU_EMULATOR_OVERRIDE) {
                        val bundledCoresDir = LaunchManager.extractBundledCores(this@MainActivity)
                        val options = platformResolver.getCorePickerOptions(game.platformTag, packageManager,
                            installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir,
                            unresponsivePackages = installedCoreService.unresponsivePackages)
                        val override = platformResolver.getGameOverride(game.file.absolutePath)
                        if (override != null) {
                            val match = if (override.appPackage != null) {
                                options.firstOrNull { it.appPackage == override.appPackage }
                            } else {
                                options.firstOrNull { it.coreId == override.coreId && (override.runner == null || it.runnerLabel == override.runner) }
                            }
                            if (match != null) {
                                val desc = if (match.appPackage != null) match.displayName
                                    else "${match.runnerLabel} (${match.displayName})"
                                "$MENU_EMULATOR_OVERRIDE\t$desc"
                            } else item
                        } else {
                            "$MENU_EMULATOR_OVERRIDE\tPlatform Default"
                        }
                    } else item
                })
                if (game.artFile != null) {
                    val idx = indexOf(MENU_DELETE_GAME)
                    if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
                }
            }
        }
    }

    companion object {
        private const val MENU_RENAME = "Rename"
        private const val MENU_DELETE = "Delete"
        private const val MENU_DELETE_GAME = "Delete Game"
        private const val MENU_DELETE_ART = "Delete Art"
        private const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
        private const val MENU_EMULATOR_OVERRIDE = "Emulator Override"
        private const val MENU_REMOVE_FROM_COLLECTION = "Remove From Collection"
        private const val MENU_CHILD_COLLECTIONS = "Child Collections"
        private const val MENU_RA_GAME_ID = "RA Game ID"
        private const val MENU_ADD_FAVORITE = "Add To Favorites"
        private const val MENU_REMOVE_FAVORITE = "Remove From Favorites"
        private const val MENU_REMOVE = "Remove Shortcut"
        private const val MENU_REMOVE_FROM_RECENTS = "Remove From Recently Played"
    }

    private val gameContextOptions = listOf(MENU_MANAGE_COLLECTIONS, MENU_EMULATOR_OVERRIDE, MENU_RA_GAME_ID, MENU_RENAME, MENU_DELETE_GAME)

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (currentScreen is LauncherScreen.ProfileList) {
            when (state.options[state.selectedOption]) {
                "Rename" -> {
                    dialogState.value = DialogState.ProfileNameInput(
                        isNew = false,
                        originalName = state.gameName,
                        currentName = state.gameName,
                        cursorPos = state.gameName.length
                    )
                }
                "Delete" -> {
                    dialogState.value = DialogState.DeleteProfileConfirm(state.gameName)
                }
            }
            return
        }
        if (currentScreen == LauncherScreen.SystemList) {
            val fghGame = pendingFghGame
            if (fghGame != null) {
                pendingFghGame = null
                onFghContextMenuConfirm(fghGame, state)
            } else {
                when (state.options[state.selectedOption]) {
                    MENU_RENAME -> {
                        dialogState.value = DialogState.RenameInput(
                            gameName = state.gameName,
                            currentName = state.gameName,
                            cursorPos = state.gameName.length
                        )
                    }
                }
            }
            return
        }
        val game = gameListViewModel.getSelectedGame() ?: return
        val glState = gameListViewModel.state.value
        pendingContextReturn = ContextReturn.Single(state.gameName, state.options, state.selectedOption)
        val selected = state.options[state.selectedOption]
        when {
            selected == MENU_REMOVE_FROM_RECENTS -> {
                pendingContextReturn = null
                dialogState.value = DialogState.None
                ioScope.launch {
                    recentlyPlayedManager.remove(game.file.absolutePath)
                    gameListViewModel.loadRecentlyPlayed()
                    rescanSystemList()
                }
                return
            }
            selected == MENU_RENAME -> {
                if (glState.isCollectionsList || game.isChildCollection) {
                    val stem = if (game.isChildCollection) game.file.name else game.file.nameWithoutExtension
                    val displayName = dev.cannoli.scorza.model.Collection.stemToDisplayName(stem)
                    dialogState.value = DialogState.CollectionRenameInput(
                        oldStem = stem,
                        currentName = displayName,
                        cursorPos = displayName.length
                    )
                } else {
                    val name = game.displayName.removePrefix("★ ")
                    dialogState.value = DialogState.RenameInput(
                        gameName = name,
                        currentName = name,
                        cursorPos = name.length
                    )
                }
            }
            selected == MENU_DELETE || selected == MENU_DELETE_GAME -> {
                if (glState.isCollectionsList || game.isChildCollection) {
                    val stem = if (game.isChildCollection) game.file.name else game.file.nameWithoutExtension
                    dialogState.value = DialogState.DeleteCollectionConfirm(collectionStem = stem)
                } else {
                    dialogState.value = DialogState.DeleteConfirm(gameName = game.displayName.removePrefix("★ "))
                }
            }
            selected == MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(listOf(game.file.absolutePath), game.displayName)
            }
            selected == MENU_CHILD_COLLECTIONS -> {
                val stem = if (game.isChildCollection) game.file.name else game.file.nameWithoutExtension
                openChildPicker(stem)
            }
            selected == MENU_DELETE_ART -> {
                pendingContextReturn = null
                game.artFile?.delete()
                scanner.invalidateArtForTag(game.platformTag)
                gameListViewModel.reload()
                dialogState.value = DialogState.None
            }
            selected == MENU_RA_GAME_ID -> {
                val current = scanner.getRaGameId(game.file.absolutePath)?.toString() ?: ""
                dialogState.value = DialogState.RenameInput(
                    gameName = "ra_game_id:${game.file.absolutePath}",
                    currentName = current,
                    cursorPos = current.length
                )
            }
            selected == MENU_REMOVE -> {
                pendingContextReturn = null
                ioScope.launch {
                    scanner.removeApkLaunch(glState.platformTag, game.displayName)
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            selected == MENU_ADD_FAVORITE || selected == MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                gameListViewModel.toggleFavorite { rescanSystemList() }
                dialogState.value = DialogState.None
            }
            selected == MENU_EMULATOR_OVERRIDE || selected.startsWith("$MENU_EMULATOR_OVERRIDE\t") -> {
                val tag = game.platformTag
                val bundledCoresDir2 = LaunchManager.extractBundledCores(this@MainActivity)
                val options = platformResolver.getCorePickerOptions(tag, packageManager,
                    installedRaCores = installedCoreService.installedCores, embeddedCoresDir = bundledCoresDir2,
                    unresponsivePackages = installedCoreService.unresponsivePackages)
                val platformCoreId = platformResolver.getCoreMapping(tag)
                val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
                val defaultLabel = if (platformCoreName.isNotEmpty()) "Platform Setting ($platformCoreName)" else "Platform Setting"
                val defaultOption = CorePickerOption("", defaultLabel, "")
                val allOptions = listOf(defaultOption) + options
                val override = platformResolver.getGameOverride(game.file.absolutePath)
                val selectedIdx = if (override?.appPackage != null) {
                    allOptions.indexOfFirst { it.appPackage == override.appPackage }.coerceAtLeast(0)
                } else if (override != null) {
                    allOptions.indexOfFirst { it.coreId == override.coreId && (it.runnerLabel == override.runner || override.runner == null) }
                        .coerceAtLeast(0)
                } else {
                    0
                }
                dialogState.value = DialogState.None
                screenStack.add(LauncherScreen.CorePicker(
                    tag = tag,
                    platformName = game.displayName,
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = game.file.absolutePath,
                    activeIndex = selectedIdx
                ))
            }
        }
    }

    private fun onDeleteConfirm(state: DialogState.DeleteConfirm) {
        pendingContextReturn = null
        if (state.bulkPaths != null) {
            val games = gameListViewModel.state.value.games
            val pathSet = state.bulkPaths.toSet()
            val toDelete = games.filter { it.file.absolutePath in pathSet }
            ioScope.launch {
                toDelete.forEach { scanner.deleteGame(it) }
                gameListViewModel.reload()
                rescanSystemList()
                withContext(Dispatchers.Main) { dialogState.value = DialogState.None }
            }
        } else {
            val game = gameListViewModel.getSelectedGame()
                ?: (systemListViewModel.getSelectedItem() as? SystemListViewModel.ListItem.GameItem)?.game
                ?: return
            ioScope.launch {
                scanner.deleteGame(game)
                gameListViewModel.reload()
                rescanSystemList()
                withContext(Dispatchers.Main) { dialogState.value = DialogState.None }
            }
        }
    }

    private fun onCollectionPickerConfirm(state: LauncherScreen.CollectionPicker) {
        val added = state.checkedIndices - state.initialChecked
        val removed = state.initialChecked - state.checkedIndices
        val toAdd = added.mapNotNull { state.collections.getOrNull(it) }
        val toRemove = removed.mapNotNull { state.collections.getOrNull(it) }
        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            ioScope.launch {
                for (path in state.gamePaths) {
                    toAdd.forEach { collName -> collectionManager.addToCollection(collName, path) }
                    toRemove.forEach { collName -> collectionManager.removeFromCollection(collName, path) }
                }
                gameListViewModel.reload()
                rescanSystemList()
            }
        }
        screenStack.removeAt(screenStack.lastIndex)
        restoreContextMenu()
    }

    private fun restoreContextMenu() {
        when (val ret = pendingContextReturn) {
            is ContextReturn.Single -> {
                val game = gameListViewModel.getSelectedGame()
                if (game != null) {
                    val glState = gameListViewModel.state.value
                    val newOptions = buildGameContextOptions(game, glState)
                    val oldSelected = ret.options.getOrNull(ret.selectedOption)
                    val restoredIdx = if (oldSelected != null) {
                        val key = oldSelected.substringBefore('\t')
                        newOptions.indexOfFirst { it.startsWith(key) }.coerceAtLeast(0)
                    } else 0
                    dialogState.value = DialogState.ContextMenu(
                        gameName = game.displayName,
                        selectedOption = restoredIdx,
                        options = newOptions
                    )
                } else {
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                }
            }
            is ContextReturn.Bulk -> {
                dialogState.value = DialogState.BulkContextMenu(
                    gamePaths = ret.gamePaths,
                    options = ret.options
                )
            }
            null -> dialogState.value = DialogState.None
        }
    }

    private fun openCollectionManager(gamePaths: List<String>, title: String) {
        val all = collectionManager.scanCollections()
            .filter { !it.stem.equals("Favorites", ignoreCase = true) }
        val stems = all.map { it.stem }
        val displayNames = all.map { it.displayName }
        val alreadyIn = if (gamePaths.size == 1) {
            collectionManager.getCollectionsContaining(gamePaths[0])
        } else {
            gamePaths.map { collectionManager.getCollectionsContaining(it) }
                .reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
        }
        val initialChecked = stems.indices
            .filter { stems[it] in alreadyIn }
            .toSet()
        dialogState.value = DialogState.None
        screenStack.add(LauncherScreen.CollectionPicker(
            gamePaths = gamePaths,
            title = title,
            collections = stems,
            displayNames = displayNames,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun openChildPicker(collectionStem: String) {
        val allStems = collectionManager.getCollectionStems()
            .filter { !it.equals("Favorites", ignoreCase = true) }
        val ancestors = collectionManager.getAncestors(collectionStem)
        val available = allStems.filter { it != collectionStem && it !in ancestors }
        val displayNames = available.map { dev.cannoli.scorza.model.Collection.stemToDisplayName(it) }
        val currentChildren = collectionManager.getChildCollections(collectionStem).toSet()
        val initialChecked = available.indices
            .filter { available[it] in currentChildren }
            .toSet()
        dialogState.value = DialogState.None
        screenStack.add(LauncherScreen.ChildPicker(
            collectionName = collectionStem,
            collections = available,
            displayNames = displayNames,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun onChildPickerConfirm(screen: LauncherScreen.ChildPicker) {
        val selected = screen.checkedIndices
            .mapNotNull { screen.collections.getOrNull(it) }
            .toSet()
        ioScope.launch {
            collectionManager.setChildCollections(screen.collectionName, selected)
            gameListViewModel.reload()
            rescanSystemList()
        }
        screenStack.removeAt(screenStack.lastIndex)
        restoreContextMenu()
    }

    private fun onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
        pendingContextReturn = ContextReturn.Bulk(state.gamePaths, state.options)
        when (state.options[state.selectedOption]) {
            MENU_REMOVE_FROM_RECENTS -> {
                pendingContextReturn = null
                dialogState.value = DialogState.None
                ioScope.launch {
                    state.gamePaths.forEach { path -> recentlyPlayedManager.remove(path) }
                    gameListViewModel.loadRecentlyPlayed()
                    rescanSystemList()
                }
                return
            }
            MENU_ADD_FAVORITE -> {
                pendingContextReturn = null
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        collectionManager.addToCollection("Favorites", path)
                    }
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            MENU_REMOVE_FAVORITE -> {
                pendingContextReturn = null
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        collectionManager.removeFromCollection("Favorites", path)
                    }
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(state.gamePaths, "${state.gamePaths.size} Selected")
            }
            MENU_DELETE_GAME -> {
                pendingContextReturn = null
                dialogState.value = DialogState.DeleteConfirm(
                    gameName = "${state.gamePaths.size} items",
                    bulkPaths = state.gamePaths
                )
            }
            MENU_DELETE_ART -> {
                pendingContextReturn = null
                val games = gameListViewModel.state.value.games
                val pathSet = state.gamePaths.toSet()
                val tagsToInvalidate = mutableSetOf<String>()
                games.filter { it.file.absolutePath in pathSet }
                    .forEach { g ->
                        g.artFile?.delete()
                        tagsToInvalidate.add(g.platformTag)
                    }
                tagsToInvalidate.forEach { scanner.invalidateArtForTag(it) }
                gameListViewModel.reload()
                dialogState.value = DialogState.None
            }
            MENU_REMOVE -> {
                pendingContextReturn = null
                val glState = gameListViewModel.state.value
                val pathSet = state.gamePaths.toSet()
                ioScope.launch {
                    glState.games.filter { it.file.absolutePath in pathSet }
                        .forEach { g ->
                            val name = g.displayName.removePrefix("★ ")
                            scanner.removeApkLaunch(glState.platformTag, name)
                        }
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            MENU_REMOVE_FROM_COLLECTION -> {
                pendingContextReturn = null
                val collName = gameListViewModel.state.value.collectionName ?: return
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        collectionManager.removeFromCollection(collName, path)
                    }
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
        }
    }


    private fun onNewCollectionConfirm(state: DialogState.NewCollectionInput) {
        val name = state.currentName.trim()
        if (name.isEmpty()) {
            dialogState.value = DialogState.None
            return
        }
        dialogState.value = DialogState.None
        ioScope.launch {
            val stem = collectionManager.createCollection(name)
            if (state.parentStem != null) {
                collectionManager.setCollectionParent(stem, state.parentStem)
            }
            state.gamePaths.forEach { path ->
                collectionManager.addToCollection(stem, path)
            }
            gameListViewModel.reload()
            rescanSystemList()
            runOnUiThread { refreshCollectionPickerOnStack() }
        }
    }

    private fun onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == dev.cannoli.scorza.model.Collection.stemToDisplayName(state.oldStem)) {
            restoreContextMenu()
            return
        }
        val glState = gameListViewModel.state.value
        val renamingFromParent = glState.isCollection && !glState.isCollectionsList
        dialogState.value = DialogState.None
        ioScope.launch {
            collectionManager.renameCollection(state.oldStem, newName)
            if (renamingFromParent) {
                gameListViewModel.reload()
            } else {
                gameListViewModel.loadCollectionsList(restoreIndex = true)
            }
        }
    }

    private fun onProfileNameConfirm(state: DialogState.ProfileNameInput) {
        val name = state.currentName.trim()
        if (name.isBlank() || dev.cannoli.scorza.input.ProfileManager.isProtected(name)) {
            dialogState.value = DialogState.None
            return
        }
        if (state.isNew) {
            val identity = controllerManager.slots[0]
            val device = controllerManager.getDeviceIdForPort(0)?.let { android.view.InputDevice.getDevice(it) }
            val verifier: (IntArray) -> BooleanArray = if (device != null) {
                { codes -> device.hasKeys(*codes) }
            } else {
                { codes -> BooleanArray(codes.size) { true } }
            }
            val match = identity?.let { profileManager.autoProfileControlsFor(it, autoconfigMatcher, verifier) }
            val controls = match?.controls ?: emptyMap()
            if (!profileManager.createProfile(name, controls)) {
                dialogState.value = DialogState.None
                return
            }
            if (match != null) {
                android.widget.Toast.makeText(this, "Prefilled with ${match.deviceName}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            val file = java.io.File(settings.sdCardRoot, "Config/Profiles/${state.originalName}.ini")
            val dest = java.io.File(settings.sdCardRoot, "Config/Profiles/$name.ini")
            if (dest.exists() && name != state.originalName) {
                dialogState.value = DialogState.None
                return
            }
            file.renameTo(dest)
        }
        val updated = profileManager.listProfiles()
        val screen = currentScreen as? LauncherScreen.ProfileList
        if (screen != null) {
            screenStack[screenStack.lastIndex] = screen.copy(
                profiles = updated,
                selectedIndex = updated.indexOf(name).coerceAtLeast(0)
            )
        }
        dialogState.value = DialogState.None
    }

    private fun onNewFolderConfirm(state: DialogState.NewFolderInput) {
        val name = state.currentName.trim()
        if (name.isBlank()) {
            dialogState.value = DialogState.None
            return
        }
        val newDir = java.io.File(state.parentPath, name)
        newDir.mkdirs()
        dialogState.value = DialogState.None
        val screen = currentScreen
        if (screen is LauncherScreen.DirectoryBrowser && screen.currentPath == state.parentPath) {
            val newEntries = listDirectories(screen.currentPath)
            screenStack[screenStack.lastIndex] = screen.copy(entries = newEntries)
        }
    }

    private fun isVolumeRoot(path: String): Boolean {
        return detectStorageVolumes().any { it.second == path }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        if (state.gameName == "ra_username") {
            settings.raUsername = state.currentName.trim()
            settingsViewModel.refreshSubList()
            dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "ra_password") {
            settingsViewModel.raPassword = state.currentName.trim()
            settingsViewModel.refreshSubList()
            dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "title") {
            settings.title = state.currentName.trim()
            settingsViewModel.refreshSubList()
            settingsViewModel.load()
            dialogState.value = DialogState.None
            return
        }
        if (state.gameName.startsWith("ra_game_id:")) {
            val romPath = state.gameName.removePrefix("ra_game_id:")
            val gameId = state.currentName.trim().toIntOrNull()
            scanner.setRaGameId(romPath, gameId)
            restoreContextMenu()
            return
        }
        if (currentScreen == LauncherScreen.SystemList) {
            onSystemListRename(state)
            return
        }
        val game = gameListViewModel.getSelectedGame() ?: return
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == game.displayName) {
            pendingContextReturn = null
            dialogState.value = DialogState.None
            return
        }

        pendingContextReturn = null
        dialogState.value = DialogState.None
        ioScope.launch {
            if (game.isSubfolder) {
                val newDir = File(game.file.parentFile, newName)
                val ok = game.file.renameTo(newDir)
                val msg = if (ok) null else "Failed to rename directory"
                withContext(Dispatchers.Main) {
                    if (msg != null) dialogState.value = DialogState.RenameResult(false, msg)
                }
            } else {
                val result = atomicRename.rename(game.file, newName, game.platformTag)
                if (!result.success) {
                    withContext(Dispatchers.Main) {
                        dialogState.value = DialogState.RenameResult(false, result.error ?: "Rename failed")
                    }
                }
            }
            scanner.invalidateArtForTag(game.platformTag)
            scanner.invalidateMapForDir(game.file.parent ?: "")
            scanner.invalidateGameCacheForTag(game.platformTag)
            gameListViewModel.reload()
        }
    }

    private fun switchPlatform(delta: Int) {
        if (navigating) return
        val items = systemListViewModel.getNavigableItems()
        if (items.size < 2) return

        val gs = gameListViewModel.state.value
        val currentIndex = items.indexOfFirst { item ->
            when {
                gs.platformTag == "recently_played" -> item is SystemListViewModel.ListItem.RecentlyPlayedItem
                gs.isCollectionsList -> item is SystemListViewModel.ListItem.CollectionsFolder
                gs.isCollection && gs.collectionName.equals("Favorites", ignoreCase = true) -> item is SystemListViewModel.ListItem.FavoritesItem
                gs.isCollection && gs.collectionName != null -> {
                    (item is SystemListViewModel.ListItem.CollectionsFolder) ||
                    (item is SystemListViewModel.ListItem.CollectionItem && item.name == gs.collectionName)
                }
                gs.platformTag == "tools" -> item is SystemListViewModel.ListItem.ToolsFolder
                gs.platformTag == "ports" -> item is SystemListViewModel.ListItem.PortsFolder
                gs.platformTag.isNotEmpty() -> item is SystemListViewModel.ListItem.PlatformItem && item.platform.tag == gs.platformTag
                else -> false
            }
        }
        if (currentIndex == -1) return

        val newIndex = (currentIndex + delta).mod(items.size)
        navigating = true
        when (val target = items[newIndex]) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                gameListViewModel.loadRecentlyPlayed {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                gameListViewModel.loadCollection("Favorites") {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                gameListViewModel.loadCollectionsList {
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                gameListViewModel.loadPlatform(target.platform.tag, target.platform.allTags) {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                gameListViewModel.loadCollection(target.name) {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                gameListViewModel.loadApkList("tools", target.name) {
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                gameListViewModel.loadApkList("ports", target.name) {
                    navigating = false
                }
            }
            else -> { navigating = false }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun queryInstalledCores() {
        unregisterCoreQueryReceiver()
        val pkgLabel = InstalledCoreService.getPackageLabel(settings.retroArchPackage)
        pushScreen(LauncherScreen.InstalledCores(title = "$pkgLabel Installed Cores"))

        ioScope.launch {
            installedCoreService.queryAllPackages()
            val allCores = installedCoreService.installedCores.flatMap { (pkg, cores) ->
                val label = InstalledCoreService.getPackageLabel(pkg)
                cores.map { coreId ->
                    val name = platformResolver.getCoreDisplayName(coreId)
                    "$name ($label)"
                }
            }.sorted()
            withContext(Dispatchers.Main) {
                val screen = screenStack.lastOrNull() as? LauncherScreen.InstalledCores ?: return@withContext
                screenStack[screenStack.lastIndex] = screen.copy(cores = allCores, loading = false)
            }
        }
    }

    private fun unregisterCoreQueryReceiver() {
        coreQueryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
            coreQueryReceiver = null
        }
    }

    private fun filterCoreMappings(all: List<dev.cannoli.scorza.ui.screens.CoreMappingEntry>, filter: Int): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Unknown" }
        else -> all
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionLauncher.launch(intent)
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

}
