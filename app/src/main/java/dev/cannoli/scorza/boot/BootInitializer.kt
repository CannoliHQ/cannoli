package dev.cannoli.scorza.boot

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.importer.ImportProgress
import dev.cannoli.scorza.db.importer.ImportResult
import dev.cannoli.scorza.db.importer.Importer
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.BindingController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.updater.UpdateManager
import dev.cannoli.scorza.util.ScanLog
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

@ActivityScoped
class BootInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val cannoliDatabase: CannoliDatabase,
    private val scanScheduler: ScanScheduler,
    private val cannoliPaths: CannoliPathsProvider,
    @IoScope private val ioScope: CoroutineScope,
    private val installedCoreService: InstalledCoreService,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val collectionsRepository: CollectionsRepository,
    private val updateManager: UpdateManager,
    private val bindingController: BindingController,
    private val nav: NavigationController,
    private val romsRepository: RomsRepository,
    private val launchManager: LaunchManager,
    private val launcherActions: LauncherActions,
) {

    suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult {
        val root = cannoliPaths.root
        val romDir = cannoliPaths.romDir
        val importPhase = if (hasLegacyData(root)) BootPhase.IMPORT else BootPhase.INITIAL_SCAN
        onPhase(BootPhase.LIBRARY_REFRESH, 0f, "Preparing")

        ScanLog.init(root.absolutePath)
        dev.cannoli.scorza.util.InputLog.init(root.absolutePath)
        platformConfig.load()
        ioScope.launch {
            launchManager.syncRetroArchAssets(root)
            launchManager.syncRetroArchConfig(root)
        }
        ioScope.launch { dev.cannoli.scorza.util.DirectoryLayout.ensure(root, romDir, context.assets, platformConfig) }

        val importer = Importer(
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
            return BootResult.Failure(result.cause.message ?: "Database import failed")
        }

        ioScope.launch {
            installedCoreService.queryAllPackages()
            platformConfig.purgeStaleRaMappings(installedCoreService.installedCores)
        }

        withContext(Dispatchers.Main) {
            gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
            settingsViewModel.reinitialize(root, context.packageManager, context.packageName, collectionsRepository)

            if (updateManager.shouldAutoCheck()) {
                ioScope.launch { updateManager.checkForUpdate() }
            }

            ioScope.launch {
                updateManager.updateAvailable.collect { info ->
                    settingsViewModel.updateInfo = info
                }
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

            val quickResume = CannoliPaths(root).quickResumeFile
            if (quickResume.exists()) {
                val lines = try { quickResume.readLines() } catch (_: Exception) { emptyList() }
                quickResume.delete()
                if (lines.size >= 2) {
                    val romFile = File(lines[0])
                    if (romFile.exists()) {
                        val rom = romsRepository.gameByPath(romFile.absolutePath)
                        if (rom != null) {
                            val errorDialog = launchManager.resumeRom(rom)
                            if (errorDialog != null) {
                                nav.dialogState.value = errorDialog
                            } else {
                                launcherActions.recordRecentlyPlayedByPath(romFile.absolutePath)
                            }
                        }
                    }
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
                onComplete = { cont.resume(BootResult.Success) },
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
