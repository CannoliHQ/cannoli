package dev.cannoli.scorza.boot

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.ConfigLayoutMigration
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.db.importer.ImportProgress
import dev.cannoli.scorza.db.importer.ImportResult
import dev.cannoli.scorza.db.importer.Importer
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.igm.BindingController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.AutoconfigSeeder
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.updater.UpdateManager
import dev.cannoli.scorza.util.KitchenLog
import dev.cannoli.scorza.util.ScanLog
import dev.cannoli.scorza.util.StorageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

sealed interface BootResult {
    data object Success : BootResult
    data class Failure(val message: String) : BootResult
}

internal fun startOnTarget(
    tag: String,
    mode: ContentMode,
    platforms: List<dev.cannoli.scorza.model.Platform>,
): dev.cannoli.scorza.model.Platform? {
    if (tag.isEmpty() || mode != ContentMode.PLATFORMS) return null
    return platforms.firstOrNull { tag in it.allTags && it.gameCount > 0 }
}

@ActivityScoped
class BootInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val cannoliDatabase: CannoliDatabase,
    private val scanScheduler: ScanScheduler,
    private val cannoliPaths: CannoliPathsProvider,
    @IoScope private val ioScope: CoroutineScope,
    private val gameListViewModel: GameListViewModel,
    private val systemListViewModel: dev.cannoli.scorza.ui.viewmodel.SystemListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val collectionsRepository: CollectionsRepository,
    private val updateManager: UpdateManager,
    private val bindingController: BindingController,
    private val nav: NavigationController,
    private val launchManager: LaunchManager,
    private val cheevosOverrideMigration: dev.cannoli.scorza.launcher.CheevosOverrideMigration,
    private val guidesKeyMigration: dev.cannoli.scorza.launcher.GuidesKeyMigration,
    private val launcherActions: LauncherActions,
    private val setupCoordinator: SetupCoordinator,
    private val autoconfigSeeder: AutoconfigSeeder,
    private val autoconfigRepository: AutoconfigRepository,
    private val romsRepository: dev.cannoli.scorza.db.RomsRepository,
    private val gameOverrides: dev.cannoli.scorza.db.GameOverrideStore,
) {

    suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult {
        val root = cannoliPaths.root
        val romDir = cannoliPaths.romDir
        // Ahead of every other line here: an older tree keeps cannoli.db and the rest at the top of
        // Config/, and the current layout expects them under Config/Internal. hasLegacyData and the
        // database both read paths that this moves, and SQLite must not have the file open yet.
        withContext(Dispatchers.IO) { ConfigLayoutMigration.run(root) }
        val importPhase = if (hasLegacyData(root)) BootPhase.IMPORT else BootPhase.INITIAL_SCAN
        onPhase(BootPhase.LIBRARY_REFRESH, 0f, context.getString(R.string.boot_preparing))

        ScanLog.init(root.absolutePath)
        dev.cannoli.scorza.util.InputLog.init(root.absolutePath)
        KitchenLog.init(root.absolutePath)
        StorageLog.init(root.absolutePath)
        dev.cannoli.scorza.util.RommLog.init(root.absolutePath)
        dev.cannoli.scorza.util.ErrorLog.init(root.absolutePath)
        withContext(Dispatchers.IO) { dev.cannoli.scorza.download.DownloadStaging.sweepOnce(root) }
        setupCoordinator.logStorageDiagnostics()
        platformConfig.load()
        platformConfig.seedUnsetPlatforms(context.packageManager)
        ioScope.launch {
            try {
                autoconfigSeeder.seedIfNeeded()
            } catch (e: Exception) {
                dev.cannoli.scorza.util.ErrorLog.write("autoconfig seed failed: ${e::class.java.simpleName} ${e.message}")
            }
            autoconfigRepository.invalidate()
            launchManager.syncRetroArchAssets(root)
            launchManager.syncBundledShaders(root)
            launchManager.ensureShaderIndex(root)
            launchManager.writeRunnerConfig(root)
            cheevosOverrideMigration.scrubIfNeeded()
        }
        ioScope.launch {
            dev.cannoli.scorza.util.DirectoryLayout.ensure(root, romDir, context.assets, platformConfig, context)
            migrateSaveStatesByCore(root)
        }

        val importer = Importer(
            context = context,
            cannoliRoot = root,
            romDirectory = romDir,
            db = cannoliDatabase,
            platformConfig = platformConfig,
            scanScheduler = scanScheduler,
            onProgress = ImportProgress { progress, label ->
                onPhase(importPhase, progress, label)
            },
            scanDisk = settings.scanLibraryAutomatically,
        )

        val result = withContext(Dispatchers.IO) { importer.run() }

        if (result is ImportResult.Failure) {
            ScanLog.write("ERROR import returned Failure: ${result.cause.message}")
            return BootResult.Failure(result.cause.message ?: context.getString(R.string.boot_import_failed))
        }

        ioScope.launch {
            guidesKeyMigration.migrateIfNeeded()
        }

        withContext(Dispatchers.Main) {
            gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
            settingsViewModel.reinitialize(
                context.packageManager, context.packageName, collectionsRepository,
            ) { systemListViewModel.state.value.platforms }

            ioScope.launch { updateManager.purgeStaleDownloads() }

            if (updateManager.shouldAutoCheck()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }

            bindingController.onProgress = { keys, elapsedMs ->
                val cs = nav.currentScreen
                if (cs is LauncherScreen.ShortcutBinding) {
                    nav.replaceTop(cs.copy(heldKeys = keys, countdownMs = elapsedMs))
                }
            }
            bindingController.onCommit = { chord ->
                val cs = nav.currentScreen
                if (cs is LauncherScreen.ShortcutBinding) {
                    val action = dev.cannoli.igm.ShortcutAction.entries.getOrNull(cs.selectedIndex)
                    if (action != null) {
                        val cleared = cs.shortcuts.filterValues { it != chord }
                        nav.replaceTop(cs.copy(
                            shortcuts = cleared + (action to chord),
                            listening = false, heldKeys = emptySet(), countdownMs = 0,
                        ))
                    }
                }
            }
            bindingController.onCancel = {
                val cs = nav.currentScreen
                if (cs is LauncherScreen.ShortcutBinding && cs.listening) {
                    nav.replaceTop(cs.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
                }
            }

            nav.screenStack.clear()
            nav.screenStack.add(LauncherScreen.SystemList)
        }

        val shouldScanDisk = settings.scanLibraryAutomatically
        return suspendCancellableCoroutine { cont ->
            launcherActions.rescanSystemList(
                scanDisk = shouldScanDisk,
                onProgress = { tag, current, total ->
                    onPhase(BootPhase.LIBRARY_REFRESH, current.toFloat() / total.coerceAtLeast(1), tag)
                },
                onComplete = { landOnStartPlatform { cont.resume(BootResult.Success) } },
            )
        }
    }

    private fun landOnStartPlatform(done: () -> Unit) {
        val platform = startOnTarget(
            settings.startOnPlatform,
            settings.contentMode,
            systemListViewModel.state.value.platforms,
        ) ?: return done()
        gameListViewModel.loadPlatform(platform.tag, platform.allTags) {
            launcherActions.scanResumableGames()
            nav.screenStack.add(LauncherScreen.GameList)
            done()
        }
    }

    private fun migrateSaveStatesByCore(root: java.io.File) {
        val paths = dev.cannoli.scorza.config.CannoliPaths(root)
        val overrides = runCatching {
            romsRepository.allRoms().associate { rom ->
                (rom.platformTag to dev.cannoli.core.RomKey.baseName(rom.path)) to
                    gameOverrides.get(rom.id)?.coreId?.ifEmpty { null }
            }
        }.getOrDefault(emptyMap())

        val result = dev.cannoli.scorza.util.SaveStateCoreMigration.run(paths) { tag, base ->
            overrides[tag to base] ?: platformConfig.getCoreName(tag)
        }
        if (result.files > 0) {
            dev.cannoli.scorza.util.ErrorLog.write(
                "save states keyed by core: ${result.files} files across ${result.games} games"
            )
        }
    }

    private fun hasLegacyData(root: File): Boolean {
        val p = CannoliPaths(root)
        return p.collectionsDir.exists() ||
            p.coresJson.exists() ||
            p.raGameIdsFile.exists() ||
            p.raGameIdsLegacyFile.exists() ||
            p.recentlyPlayedFile.exists() ||
            p.configOrdering.exists() ||
            p.toolsDir.exists() ||
            p.portsDir.exists() ||
            p.platformCacheFile.exists() ||
            p.gameCacheFile.exists()
    }
}
