package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import dev.cannoli.scorza.ui.screens.MappingActionKind
import dev.cannoli.scorza.ui.screens.MappingItem
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

    fun buildPlatformMapping(
        tag: String,
        platformName: String,
        showAll: Boolean,
        selectedIndex: Int = 0,
        scrollTarget: Int = 0,
    ): LauncherScreen.PlatformMapping {
        val bundled = LaunchManager.extractBundledCores(context)
        val installedRaCores = installedCoreService.configuredCores()
        val sources = platformConfig.availableSources(tag = tag, embeddedCoresDir = bundled)
        val currentRunner = platformConfig.getRunnerLabel(tag, platformConfig.getCoreMapping(tag), installedRaCores)
        val currentSource = EmulatorSource.fromRunnerLabel(currentRunner)
        val currentCoreId = platformConfig.getCoreMapping(tag)
        val currentApp = platformConfig.getAppPackage(tag)

        fun isCurrent(opt: dev.cannoli.scorza.ui.screens.EmulatorPickerOption): Boolean {
            val optSource = EmulatorSource.fromRunnerLabel(opt.runnerLabel) ?: return false
            if (optSource != currentSource) return false
            return when (optSource) {
                EmulatorSource.Standalone -> opt.appPackage == currentApp
                else -> opt.coreId == currentCoreId
            }
        }

        val raLabel = InstalledCoreService.getPackageLabel(settings.retroArchPackage).uppercase()
        val items = mutableListOf<MappingItem>()
        for (source in sources) {
            // Internal cores are bundled-or-nothing; Show All never expands them since
            // there is no install path for a core Cannoli does not ship.
            val includeAll = showAll && source != EmulatorSource.Internal
            val options = platformConfig.emulatorOptionsForSource(
                tag = tag, source = source, includeAll = includeAll,
                installedRaCores = installedRaCores,
                embeddedCoresDir = bundled, pm = context.packageManager,
            )
            if (options.isEmpty()) continue
            val header = when (source) {
                EmulatorSource.Internal -> EmulatorSource.Internal.displayName.uppercase()
                EmulatorSource.RetroArch -> raLabel
                EmulatorSource.Standalone -> EmulatorSource.Standalone.displayName.uppercase()
            }
            items.add(MappingItem.SectionHeader(header))
            options.forEach { items.add(MappingItem.EmulatorOption(it, isCurrent(it))) }
        }

        items.add(MappingItem.Divider())

        val biosApplicable = currentSource == EmulatorSource.Internal ||
            RetroArchLauncher.isRicotta(settings.retroArchPackage)
        if (biosApplicable && currentCoreId.isNotBlank()) {
            val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
            val firmware = platformConfig.getFirmwareStatus(currentCoreId, biosDir)
            val requiredMissing = firmware.count { (entry, present) -> !entry.optional && !present }
            val warning = requiredMissing > 0
            val status = if (warning) context.getString(dev.cannoli.scorza.R.string.mapping_required_missing, requiredMissing) else ""
            items.add(MappingItem.Action(MappingActionKind.BIOS, context.getString(dev.cannoli.scorza.R.string.mapping_action_bios), status, warning))
        }

        val overridesCount = platformConfig.getPlatformOverrides(tag).size
        val overridesLabel = context.resources.getQuantityString(
            dev.cannoli.scorza.R.plurals.override_game_count, overridesCount, overridesCount,
        )
        items.add(MappingItem.Action(MappingActionKind.OVERRIDES, context.getString(dev.cannoli.scorza.R.string.mapping_action_overrides), overridesLabel))

        val resettable = platformConfig.hasUserMapping(tag)
        if (resettable) {
            items.add(MappingItem.Action(MappingActionKind.RESET, context.getString(dev.cannoli.scorza.R.string.mapping_action_reset)))
        }

        val selectableCount = items.count { it.isSelectable }
        return LauncherScreen.PlatformMapping(
            tag = tag,
            platformName = platformName,
            items = items,
            showAll = showAll,
            overridesCount = overridesCount,
            resettable = resettable,
            selectedIndex = selectedIndex.coerceIn(0, (selectableCount - 1).coerceAtLeast(0)),
            scrollTarget = scrollTarget,
        )
    }

    fun buildBiosStatus(tag: String, platformName: String): LauncherScreen.BiosStatus {
        val coreId = platformConfig.getCoreMapping(tag)
        val installedRaCores = installedCoreService.configuredCores()
        val runner = platformConfig.getRunnerLabel(tag, coreId, installedRaCores)
        val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
        val firmware = platformConfig.getFirmwareStatus(coreId, biosDir)
            .map { dev.cannoli.scorza.ui.screens.FirmwareStatus(it.first, it.second) }
        return LauncherScreen.BiosStatus(
            tag = tag,
            platformName = platformName,
            coreDisplayName = platformConfig.getCoreDisplayName(coreId),
            runnerLabel = runner,
            firmware = firmware,
        )
    }
}
