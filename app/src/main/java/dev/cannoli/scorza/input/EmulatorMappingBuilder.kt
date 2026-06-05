package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import java.io.File
import javax.inject.Inject

@ActivityScoped
class EmulatorMappingBuilder @Inject constructor(
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val settings: SettingsRepository,
    @ActivityContext private val context: Context,
) {
    fun detailedMappings(): List<EmulatorMappingEntry> = platformConfig.getDetailedMappings(
        context.packageManager,
        installedCoreService.configuredCores(),
        LaunchManager.extractBundledCores(context),
        installedCoreService.configuredUnresponsive(),
    )

    fun filter(all: List<EmulatorMappingEntry>, filter: Int): List<EmulatorMappingEntry> = when (filter) {
        1 -> all.filter { it.status != dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.READY }
        2 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.READY }
        else -> all
    }

    fun buildPlatformDetail(tag: String, platformName: String): LauncherScreen.PlatformDetail {
        val bundled = LaunchManager.extractBundledCores(context)
        val sources = platformConfig.availableSources(tag = tag, embeddedCoresDir = bundled)
        val currentRunner = platformConfig.getRunnerLabel(
            tag,
            platformConfig.getCoreMapping(tag),
            installedCoreService.configuredCores(),
        )
        val currentSource = EmulatorSource.fromRunnerLabel(currentRunner)
            ?: sources.firstOrNull()
        val options = currentSource?.let {
            platformConfig.emulatorOptionsForSource(
                tag = tag, source = it, includeAll = false,
                installedRaCores = installedCoreService.configuredCores(),
                embeddedCoresDir = bundled, pm = context.packageManager,
            )
        } ?: emptyList()
        val pendingPick = options.firstOrNull { opt ->
            when (currentSource) {
                EmulatorSource.Standalone ->
                    opt.appPackage == platformConfig.getAppPackage(tag)
                else -> opt.coreId == platformConfig.getCoreMapping(tag)
            }
        }
        val currentEmulatorLabel = pendingPick?.displayName
            ?: currentSource?.let { context.getString(it.emptyMessageRes) } ?: "Needs setup"
        val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
        val coreId = platformConfig.getCoreMapping(tag)
        val missing = if (coreId.isNotBlank()) platformConfig.getMissingFirmware(coreId, biosDir) else emptyList()
        val biosStatus = when {
            missing.isEmpty() && !biosDir.exists() -> "Not required"
            missing.isEmpty() -> "All present"
            else -> "${missing.size} missing"
        }
        val overridesCount = platformConfig.getPlatformOverrides(tag).size
        val currentSourceLabel = currentSource?.let {
            if (it == EmulatorSource.RetroArch && currentRunner.isNotEmpty()) currentRunner
            else it.displayName
        } ?: "..."
        return LauncherScreen.PlatformDetail(
            tag = tag,
            platformName = platformName,
            availableSources = sources,
            currentSource = currentSource,
            currentSourceLabel = currentSourceLabel,
            currentEmulatorLabel = currentEmulatorLabel,
            emulatorRowPickable = options.size != 1,
            biosStatus = biosStatus,
            overridesCount = overridesCount,
            pendingPick = pendingPick,
            resettable = platformConfig.hasUserMapping(tag),
            dirty = false,
        )
    }
}
