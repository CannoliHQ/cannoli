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
import dev.cannoli.scorza.ui.screens.MappingActionKind
import dev.cannoli.scorza.ui.screens.MappingItem
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
    // Derived from the configured package on every read, so switching RicottaArch to stock
    // RetroArch relabels everything instead of leaving stale captions behind.
    private val raLabel: String get() = InstalledCoreService.getPackageLabel(settings.retroArchPackage)

    /** Per-game override rows for a platform, resolved through the rom_id join. */
    fun overrideRows(tag: String): List<dev.cannoli.scorza.ui.screens.GameOverrideRow> =
        gameOverrideStore.forPlatform(tag).map { entry ->
            dev.cannoli.scorza.ui.screens.GameOverrideRow(
                romId = entry.romId,
                gameName = entry.displayName,
                label = platformConfig.describeChoice(entry.choice, context.packageManager, raLabel),
            )
        }

    fun detailedMappings(): List<EmulatorMappingEntry> {
        val entries = platformConfig.getDetailedMappings(
            context.packageManager,
            installedCoreService.configuredCores(),
            LaunchManager.extractBundledCores(context),
            installedCoreService.configuredUnresponsive(),
        )
        // MAME and FBN are both named Arcade; tag the name so the two rows are tellable apart.
        val shared = entries.groupingBy { it.platformName }.eachCount().filterValues { it > 1 }.keys
        if (shared.isEmpty()) return entries
        return entries.map { entry ->
            if (entry.platformName in shared) {
                entry.copy(platformName = context.getString(
                    dev.cannoli.scorza.R.string.mapping_platform_name_with_tag,
                    entry.platformName, entry.tag,
                ))
            } else {
                entry
            }
        }.sortedNatural { it.platformName }
    }

    fun filter(all: List<EmulatorMappingEntry>, filter: Int): List<EmulatorMappingEntry> = when (filter) {
        1 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NOT_INSTALLED }
        2 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.NEEDS_SETUP }
        3 -> all.filter { it.status == dev.cannoli.scorza.ui.screens.EmulatorMappingStatus.READY }
        else -> all
    }

    fun buildPlatformMapping(
        tag: String,
        platformName: String,
        showAll: Boolean,
        selectedIndex: Int = 0,
        scrollTarget: Int = 0,
        defaultShowAllIfEmpty: Boolean = false,
        romId: Long? = null,
        gameName: String? = null,
        selectCurrent: Boolean = false,
    ): LauncherScreen.PlatformMapping {
        val bundled = LaunchManager.extractBundledCores(context)
        val installedRaCores = installedCoreService.configuredCores()
        val sources = platformConfig.availableSources(tag = tag, embeddedCoresDir = bundled)
        // Game scope reads the per-game override; platform scope reads the platform choice.
        val current =
            if (romId != null) gameOverrideStore.get(romId) else platformConfig.getPlatformChoice(tag)
        val currentSource = current?.source
        val currentCoreId = platformConfig.getCoreMapping(tag)

        // Matches on the stored identity, not on a caption parsed back out of the row label.
        fun isCurrent(opt: dev.cannoli.scorza.ui.screens.EmulatorPickerOption): Boolean {
            val cur = current ?: return false
            if (opt.source != cur.source) return false
            return when (cur.source) {
                EmulatorSource.Standalone -> opt.appPackage == cur.appPackage
                else -> opt.coreId == cur.coreId
            }
        }

        fun emulatorSection(useShowAll: Boolean): List<MappingItem> {
            val section = mutableListOf<MappingItem>()
            for (source in sources) {
                // Internal cores are bundled-or-nothing; Show All never expands them since
                // there is no install path for a core Cannoli does not ship.
                val includeAll = useShowAll && source != EmulatorSource.Internal
                var options = platformConfig.emulatorOptionsForSource(
                    tag = tag, source = source, includeAll = includeAll,
                    installedRaCores = installedRaCores,
                    embeddedCoresDir = bundled, pm = context.packageManager, raLabel = raLabel,
                )
                // Always surface the current mapping even when its core/app is not installed
                // and Show All is off, so a missing or bundled-default mapping stays visible.
                if (source == currentSource && options.none { isCurrent(it) }) {
                    platformConfig.emulatorOptionsForSource(
                        tag = tag, source = source, includeAll = true,
                        installedRaCores = installedRaCores,
                        embeddedCoresDir = bundled, pm = context.packageManager, raLabel = raLabel,
                    ).firstOrNull { isCurrent(it) }?.let { options = options + it }
                }
                if (options.isEmpty()) continue
                val header = when (source) {
                    EmulatorSource.Internal -> EmulatorSource.Internal.displayName
                    EmulatorSource.RetroArch -> raLabel
                    EmulatorSource.Standalone -> EmulatorSource.Standalone.displayName
                }
                section.add(MappingItem.SectionHeader(header))
                options.forEach {
                    section.add(MappingItem.EmulatorOption(
                        it, isCurrent(it),
                        downloadable = isDownloadable(source, it.available, settings.retroArchPackage, context.packageName),
                    ))
                }
            }
            return section
        }

        var effectiveShowAll = showAll
        var section = emulatorSection(effectiveShowAll).toMutableList()
        if (defaultShowAllIfEmpty && !effectiveShowAll &&
            section.none { it is MappingItem.EmulatorOption }
        ) {
            effectiveShowAll = true
            section = emulatorSection(true).toMutableList()
        }

        // A stored choice must always render, even when the option generator can no longer
        // produce it: a shipped update can drop an app from platforms.json, and a hand-edited
        // cores.json can name a core that is not a candidate for the tag. Without this the
        // screen reads as unset while the choice is still live at launch.
        if (current != null && section.none { it is MappingItem.EmulatorOption && it.isCurrent }) {
            synthesizeCurrentRow(current)?.let { row ->
                val header = when (current.source) {
                    EmulatorSource.Internal -> EmulatorSource.Internal.displayName
                    EmulatorSource.RetroArch -> raLabel
                    EmulatorSource.Standalone -> EmulatorSource.Standalone.displayName
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

        // Installed-only is always a subset of Show All, so equal option counts mean the
        // toggle would not change anything and the legend is hidden.
        val optionCount = section.count { it is MappingItem.EmulatorOption }
        val canToggleShowAll =
            emulatorSection(!effectiveShowAll).count { it is MappingItem.EmulatorOption } != optionCount

        val items = mutableListOf<MappingItem>()
        if (romId != null) {
            val platformLabel = platformConfig.detailedMappingFor(
                tag, context.packageManager, installedRaCores, bundled,
                installedCoreService.configuredUnresponsive(), raLabel,
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

        // BIOS, per-game overrides and Reset are platform-wide actions and have no meaning
        // when the screen is scoped to a single game.
        if (romId == null) {
            items.add(MappingItem.Divider())

            // Firmware belongs to the selected core, so gate on the actual choice. Gating on
            // isRicotta made every platform show BIOS, including standalone ones, and reading
            // the core from the platform default reported firmware for a core nobody picked.
            val biosCore = current?.takeIf { it.source != EmulatorSource.Standalone }?.coreId.orEmpty()
            if (biosCore.isNotBlank()) {
                val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
                val firmware = platformConfig.getFirmwareStatus(biosCore, biosDir)
                val requiredMissing = firmware.count { (entry, present) -> !entry.optional && !present }
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
            showAll = effectiveShowAll,
            canToggleShowAll = canToggleShowAll,
            overridesCount = overridesCount,
            resettable = resettable,
            romId = romId,
            gameName = gameName,
            selectedIndex = resolvedIndex.coerceIn(0, (selectableCount - 1).coerceAtLeast(0)),
            scrollTarget = scrollTarget,
        )
    }

    // Every rebuild goes through this so no call site can drop tag or scope and silently turn a
    // game-scoped screen into a platform-scoped one, which would write the wrong mapping.
    fun rebuild(
        screen: LauncherScreen.PlatformMapping,
        showAll: Boolean = screen.showAll,
        selectedIndex: Int = screen.selectedIndex,
        scrollTarget: Int = screen.scrollTarget,
    ): LauncherScreen.PlatformMapping = buildPlatformMapping(
        tag = screen.tag,
        platformName = screen.platformName,
        showAll = showAll,
        selectedIndex = selectedIndex,
        scrollTarget = scrollTarget,
        romId = screen.romId,
        gameName = screen.gameName,
    )

    private fun synthesizeCurrentRow(current: dev.cannoli.scorza.config.EmulatorChoice): MappingItem.EmulatorOption? {
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
            EmulatorSource.Internal -> EmulatorSource.Internal.displayName
            EmulatorSource.RetroArch -> raLabel
            EmulatorSource.Standalone -> EmulatorSource.Standalone.displayName
        }
        return MappingItem.EmulatorOption(
            dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                coreId = current.coreId, displayName = name,
                source = current.source, runnerLabel = label,
                appPackage = current.appPackage, available = false,
            ),
            isCurrent = true,
            downloadable = isDownloadable(current.source, false, settings.retroArchPackage, context.packageName),
        )
    }

    fun buildBiosStatus(tag: String, platformName: String): LauncherScreen.BiosStatus {
        // Reads the same core the BIOS row gated on. Deriving it separately is how the row and
        // the detail screen come to disagree.
        val coreId = platformConfig.getPlatformChoice(tag)
            ?.takeIf { it.source != EmulatorSource.Standalone }
            ?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreMapping(tag)
        val runner = platformConfig.getRunnerLabel(tag, coreId, raLabel)
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

    companion object {
        // Downloadable only for the embedded RetroArch: cores for a stock RetroArch install are
        // the user's own to manage, so Cannoli offers no download affordance for those.
        fun isDownloadable(source: EmulatorSource, available: Boolean, raPackage: String, packageName: String): Boolean =
            source == EmulatorSource.RetroArch && !available && (raPackage.isEmpty() || raPackage == packageName)
    }
}
