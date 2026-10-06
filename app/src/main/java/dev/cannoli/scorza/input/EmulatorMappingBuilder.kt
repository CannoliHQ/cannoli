package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.launcher.CoreUsage
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.CoreAvailability
import dev.cannoli.scorza.ui.screens.EmulatorMappingEntry
import dev.cannoli.scorza.ui.screens.MappingActionKind
import dev.cannoli.scorza.ui.screens.MappingItem
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.sortedNatural
import java.io.File
import javax.inject.Inject

@ActivityScoped
class EmulatorMappingBuilder @Inject constructor(
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val settings: SettingsRepository,
    private val gameOverrideStore: dev.cannoli.scorza.db.GameOverrideStore,
    @ActivityContext private val context: Context,
) {
    fun overrideRows(tag: String): List<dev.cannoli.scorza.ui.screens.GameOverrideRow> =
        gameOverrideStore.forPlatform(tag).map { entry ->
            dev.cannoli.scorza.ui.screens.GameOverrideRow(
                romId = entry.romId,
                gameName = entry.displayName,
                label = platformConfig.describeChoice(entry.choice, context.packageManager),
            )
        }

    fun detailedMappings(alphabetical: Boolean = false): List<EmulatorMappingEntry> {
        val entries = platformConfig.getDetailedMappings(
            context.packageManager,
            LaunchManager.extractBundledCores(context),
        )
        val shared = entries.groupingBy { it.platformName }.eachCount().filterValues { it > 1 }.keys
        if (shared.isEmpty()) return order(entries, alphabetical)
        return entries.map { entry ->
            if (entry.platformName in shared) {
                entry.copy(platformName = context.getString(
                    dev.cannoli.scorza.R.string.mapping_platform_name_with_tag,
                    entry.platformName, entry.tag,
                ))
            } else {
                entry
            }
        }.let { order(it, alphabetical) }
    }

    private fun order(entries: List<EmulatorMappingEntry>, alphabetical: Boolean): List<EmulatorMappingEntry> =
        if (alphabetical) entries.sortedNatural { it.platformName }
        else entries.sortedWith(
            compareBy<EmulatorMappingEntry> { platformConfig.groupRank(it.group) }
                .thenBy(NaturalSort) { it.platformName },
        )

    fun filter(all: List<EmulatorMappingEntry>, filter: Int): List<EmulatorMappingEntry> = when (filter) {
        1 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED }
        2 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NEEDS_SETUP }
        3 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.READY }
        else -> all
    }

    fun buildPlatformMapping(
        tag: String,
        platformName: String,
        selectedIndex: Int = 0,
        scrollTarget: Int = 0,
        romId: Long? = null,
        gameName: String? = null,
        selectCurrent: Boolean = false,
    ): LauncherScreen.PlatformMapping {
        val bundled = LaunchManager.extractBundledCores(context)
        val sources = platformConfig.availableSources(tag = tag, embeddedCoresDir = bundled)
        val current =
            if (romId != null) gameOverrideStore.get(romId) else platformConfig.getPlatformChoice(tag)
        val currentSource = current?.source
        val currentCoreId = platformConfig.getCoreMapping(tag)

        fun isCurrent(opt: dev.cannoli.scorza.ui.screens.EmulatorPickerOption): Boolean {
            val cur = current ?: return false
            if (opt.source != cur.source) return false
            return when (cur.source) {
                EmulatorSource.Standalone -> opt.appPackage == cur.appPackage
                EmulatorSource.Embedded -> opt.coreId == cur.coreId
            }
        }

        fun emulatorSection(useShowAll: Boolean): List<MappingItem> {
            val section = mutableListOf<MappingItem>()
            for (source in sources) {
                val includeAll = useShowAll
                var options = platformConfig.emulatorOptionsForSource(
                    tag = tag, source = source, includeAll = includeAll,
                    embeddedCoresDir = bundled, pm = context.packageManager,
                )
                if (source == currentSource && options.none { isCurrent(it) }) {
                    platformConfig.emulatorOptionsForSource(
                        tag = tag, source = source, includeAll = true,
                        embeddedCoresDir = bundled, pm = context.packageManager,
                    ).firstOrNull { isCurrent(it) }?.let { options = options + it }
                }
                if (options.isEmpty()) continue
                val header = when (source) {
                    EmulatorSource.Embedded -> context.getString(EmulatorSource.Embedded.labelRes)
                    EmulatorSource.Standalone -> context.getString(EmulatorSource.Standalone.labelRes)
                }
                section.add(MappingItem.SectionHeader(header))
                options.forEach {
                    section.add(MappingItem.EmulatorOption(
                        it, isCurrent(it),
                        downloadable = isDownloadable(source, it.availability),
                    ))
                }
            }
            return section
        }

        val section = emulatorSection(true).toMutableList()

        if (current != null && section.none { it is MappingItem.EmulatorOption && it.isCurrent }) {
            synthesizeCurrentRow(current)?.let { row ->
                val header = when (current.source) {
                    EmulatorSource.Embedded -> context.getString(EmulatorSource.Embedded.labelRes)
                    EmulatorSource.Standalone -> context.getString(EmulatorSource.Standalone.labelRes)
                }
                val at = section.indexOfFirst { it is MappingItem.SectionHeader && it.label == header }
                if (at >= 0) {
                    section.add(at + 1, row)
                } else {
                    section.add(MappingItem.SectionHeader(header))
                    section.add(row)
                }
            }
        }

        val items = mutableListOf<MappingItem>()
        if (romId != null) {
            val platformLabel = platformConfig.detailedMappingFor(
                tag, context.packageManager, bundled,
            ).coreDisplayName
            val label = if (platformLabel.isNotEmpty()) {
                context.getString(dev.cannoli.scorza.R.string.emulator_platform_setting_named, platformLabel)
            } else {
                context.getString(dev.cannoli.scorza.R.string.emulator_platform_setting)
            }
            items.add(MappingItem.PlatformDefault(label, isCurrent = current == null))
            items.add(MappingItem.Divider())
        }
        items.addAll(section)

        val overridesCount = gameOverrideStore.countForPlatform(tag)
        val resettable = platformConfig.hasUserMapping(tag)

        if (romId == null) {
            items.add(MappingItem.Divider())

            val biosCore = current?.takeIf { it.source != EmulatorSource.Standalone }?.coreId.orEmpty()
            if (biosCore.isNotBlank()) {
                val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
                val firmware = platformConfig.getFirmwareStatus(tag, biosCore, biosDir)
                val requiredMissing = firmware.count { !it.satisfied }
                val warning = requiredMissing > 0
                val status = if (warning) context.getString(dev.cannoli.scorza.R.string.mapping_required_missing, requiredMissing) else ""
                items.add(MappingItem.Action(MappingActionKind.BIOS, context.getString(dev.cannoli.scorza.R.string.mapping_action_bios), status, warning))
            }

            val overridesLabel = context.resources.getQuantityString(
                dev.cannoli.scorza.R.plurals.override_game_count, overridesCount, overridesCount,
            )
            items.add(MappingItem.Action(MappingActionKind.OVERRIDES, context.getString(dev.cannoli.scorza.R.string.mapping_action_overrides), overridesLabel))

            if (resettable) {
                items.add(MappingItem.Action(MappingActionKind.RESET, context.getString(dev.cannoli.scorza.R.string.mapping_action_reset)))
            }
        }

        val selectable = items.filter { it.isSelectable }
        val currentIdx = selectable.indexOfFirst {
            (it is MappingItem.EmulatorOption && it.isCurrent) || (it is MappingItem.PlatformDefault && it.isCurrent)
        }
        val resolvedIndex = if (selectCurrent && currentIdx >= 0) currentIdx else selectedIndex
        val selectableCount = selectable.size
        return LauncherScreen.PlatformMapping(
            tag = tag,
            platformName = platformName,
            items = items,
            overridesCount = overridesCount,
            resettable = resettable,
            romId = romId,
            gameName = gameName,
            selectedIndex = resolvedIndex.coerceIn(0, (selectableCount - 1).coerceAtLeast(0)),
            scrollTarget = scrollTarget,
        )
    }

    fun rebuild(
        screen: LauncherScreen.PlatformMapping,
        selectedIndex: Int = screen.selectedIndex,
        scrollTarget: Int = screen.scrollTarget,
    ): LauncherScreen.PlatformMapping = buildPlatformMapping(
        tag = screen.tag,
        platformName = screen.platformName,
        selectedIndex = selectedIndex,
        scrollTarget = scrollTarget,
        romId = screen.romId,
        gameName = screen.gameName,
    )

    private fun synthesizeCurrentRow(
        current: dev.cannoli.scorza.config.EmulatorChoice,
    ): MappingItem.EmulatorOption? {
        val name = when (current.source) {
            EmulatorSource.Standalone -> {
                val pkg = current.appPackage ?: return null
                runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                }.getOrNull() ?: pkg
            }
            else -> platformConfig.getCoreDisplayName(current.coreId)
        }
        val label = when (current.source) {
            EmulatorSource.Embedded -> context.getString(EmulatorSource.Embedded.labelRes)
            EmulatorSource.Standalone -> context.getString(EmulatorSource.Standalone.labelRes)
        }
        val availability = CoreAvailability.UNAVAILABLE
        return MappingItem.EmulatorOption(
            dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                coreId = current.coreId, displayName = name,
                source = current.source, runnerLabel = label,
                appPackage = current.appPackage, availability = availability,
            ),
            isCurrent = true,
            downloadable = isDownloadable(current.source, availability),
        )
    }

    fun buildBiosStatus(tag: String, platformName: String): LauncherScreen.BiosStatus {
        val coreId = platformConfig.getPlatformChoice(tag)
            ?.takeIf { it.source != EmulatorSource.Standalone }
            ?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreMapping(tag)
        val runner = platformConfig.getRunnerLabel(tag, coreId)
        val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
        val firmware = platformConfig.getFirmwareStatus(tag, coreId, biosDir).flatMap { req ->
            when (req) {
                is dev.cannoli.scorza.config.FirmwareRequirement.Single ->
                    listOf(dev.cannoli.scorza.ui.screens.FirmwareStatus(req.entry, req.present))
                is dev.cannoli.scorza.config.FirmwareRequirement.AnyOf -> req.options.map { (entry, present) ->
                    dev.cannoli.scorza.ui.screens.FirmwareStatus(entry, present, anyOf = true, groupSatisfied = req.satisfied)
                }
            }
        }
        return LauncherScreen.BiosStatus(
            tag = tag,
            platformName = platformName,
            coreDisplayName = platformConfig.getCoreDisplayName(coreId),
            runnerLabel = runner,
            firmware = firmware,
        )
    }

    companion object {
        fun isDownloadable(source: EmulatorSource, availability: CoreAvailability): Boolean =
            source == EmulatorSource.Embedded && availability != CoreAvailability.AVAILABLE
    }

    /**
     * Every installed core with what still points at it.
     *
     * getCoreMapping rather than the raw pick, because it folds in the built-in default: a platform
     * nobody has configured still runs a core, and reading explicit choices alone would offer to
     * delete the default core of every platform the user never opened the picker for.
     */
    fun buildInstalledCores(): LauncherScreen.InstalledCores {
        val coresDir = File(context.filesDir, "cores")
        val tags = platformConfig.getAllTags()
        val claims = mutableMapOf<String, MutableList<String>>()
        for (tag in tags) {
            val name = platformConfig.getDisplayName(tag)
            platformConfig.getCoreMapping(tag).takeIf { it.isNotBlank() }?.let {
                claims.getOrPut(it) { mutableListOf() }.add(name)
            }
            for (entry in gameOverrideStore.forPlatform(tag)) {
                entry.choice?.coreId?.takeIf { it.isNotBlank() }?.let {
                    claims.getOrPut(it) { mutableListOf() }.add(name)
                }
            }
        }
        val rows = CoreUsage.rows(
            installed = installedCoreService.embeddedCores(),
            sizeOf = { File(coresDir, "${it}_android.so").length() },
            displayNameOf = { platformConfig.getCoreDisplayName(it) },
            usedBy = { claims[it]?.distinct()?.sorted().orEmpty() },
        )
        return LauncherScreen.InstalledCores(
            rows = rows,
            totalBytes = rows.sumOf { it.sizeBytes },
            reclaimableBytes = CoreUsage.reclaimableBytes(rows),
        )
    }
}
