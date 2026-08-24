package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.igm.BatteryDisplayMode
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.TimeFormatMode
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.LaunchMethod
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.scorza.launcher.toIgmInputMapping
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.core.SaveSlotStore
import dev.cannoli.core.config.RetroArchConfigComposer
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.i18n.RetroArchLanguage
import dev.cannoli.scorza.settings.IgmSettingsMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.util.ArchiveExtractor
import dev.cannoli.scorza.util.parseM3uDiscPaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// Matches the line ricotta_ra_save_override writes on the tiers it owns, so every generated
// override file says the same thing about who wrote it and where a user's own keys belong.
private const val TIER_BANNER =
    "# DO NOT EDIT - Cannoli writes this from your menu choices. Your own keys go in custom.cfg\n"

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val retroArchLauncher: RetroArchLauncher,
    private val emuLauncher: EmuLauncher,
    private val apkLauncher: ApkLauncher,
    private val delfinoLauncher: DelfinoLauncher,
    private val launchState: LaunchState,
    private val activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder,
    private val atomicRename: dev.cannoli.scorza.util.AtomicRename,
    private val installedCoreService: InstalledCoreService? = null,
    private val gameOverrides: dev.cannoli.scorza.db.GameOverrideStore? = null,
) {
    // Per-game overrides are keyed by rom_id, which the scanner keeps stable across renames,
    // moves and auto-organize, so an override follows its game instead of being orphaned.
    private fun overrideFor(rom: Rom): dev.cannoli.scorza.config.EmulatorChoice? =
        gameOverrides?.get(rom.id)

    // A manual RA Game ID is an unofficial association (ROM hack or hand match) where hardcore is
    // invalid, so it forces softcore additively without touching the user's stored forceSoftcore.
    private fun cheevosFor(rom: Rom): Map<String, String> = cheevosOverrides(
        username = settings.raUsername,
        token = settings.raToken,
        hardcore = settings.raHardcore,
        forceSoftcore = rom.forceSoftcore || rom.raGameId != null,
    )

    private var raConfigPath: String? = null

    init {
        apkLauncher.debugLog = ::debugLog
    }

    fun syncRetroArchAssets(root: File) {
        val fontDest = CannoliPaths(root).cannoliFont
        if (fontDest.exists()) return
        fontDest.parentFile?.mkdirs()
        try {
            context.assets.open("fonts/MPlus-1c-NerdFont-Bold.ttf").use { input ->
                fontDest.outputStream().use { input.copyTo(it) }
            }
        } catch (_: IOException) {}
    }

    /**
     * Ensures the embedded RetroArch has a config to load.
     *
     * This used to seed itself from whichever external RetroArch the global setting named, and
     * re-sync whenever that file changed. With the source split there is no single external
     * RetroArch to copy from, and Cannoli owns the embedded runner's config outright, so it is
     * regenerated on every launch: an install upgraded from an older build gets today's default
     * keys immediately instead of carrying whatever it happened to have on disk forever.
     */
    fun syncRetroArchConfig(root: File) {
        val raDir = CannoliPaths(root).configRetroArch
        raDir.mkdirs()
        val localConfig = File(raDir, "retroarch.cfg")
        localConfig.writeText(
            "# DO NOT EDIT - Cannoli regenerates this. Your own keys go in custom.cfg\n" +
                buildMinimalConfig(root.absolutePath)
        )
        raConfigPath = localConfig.absolutePath
    }

    // Optional: a tier the user or Kitchen never wrote contributes nothing rather than failing
    // the launch. Parse is already lenient about malformed lines within a file that does exist.
    private fun readOverrideLayer(file: File): Map<String, String> =
        if (!file.exists()) emptyMap()
        else try { RetroArchConfigComposer.parse(file.readText()) } catch (_: IOException) { emptyMap() }

    private fun buildGameConfig(rom: Rom, core: String, resume: Boolean = false, slot: Int = 0): String? {
        val base = raConfigPath ?: return null
        val baseConfig = try { File(base).readText() } catch (_: IOException) { return null }
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        // The preference stack, weakest to strongest: global, then this platform on this core,
        // then this one game on this core, then the user's own custom.cfg. The plumbing band
        // applied below always wins over all four - see applyOverrides below and #36 in the launch
        // config design. Both middle tiers are keyed by core because the values that differ
        // between cores are exactly the ones worth overriding: run-ahead compensates a specific
        // core's internal latency, and a core-agnostic tier sitting above a core-specific one
        // would let the wrong value win.
        writeGlobalDefaults(paths)
        val preferenceBase = RetroArchConfigComposer.compose(
            baseConfig,
            listOf(
                readOverrideLayer(paths.globalOverrideCfg),
                readOverrideLayer(paths.systemOverrideCfg(rom.platformTag, core)),
                readOverrideLayer(paths.gameOverrideCfg(rom.platformTag, romName, core)),
                readOverrideLayer(paths.customCfg),
            ),
        )
        val stateDir = paths.saveStateDir(rom.platformTag, romName)
        stateDir.mkdirs()
        val saveDir = paths.savesFor(rom.platformTag)
        saveDir.mkdirs()
        val biosDir = paths.biosFor(rom.platformTag)
        biosDir.mkdirs()
        val raSlot = if (slot > 0) slot - 1 else 0
        val cheevos = cheevosFor(rom)
        val hardcore = hardcoreInEffect(cheevos)
        val gameOverrides = buildMap {
            put("system_directory", biosDir.absolutePath)
            put("savestate_directory", stateDir.absolutePath)
            put("sort_savestates_enable", "false")
            put("sort_savestates_by_content_enable", "false")
            // Named outright rather than derived. RetroArch's by-content sorting appends the ROM's
            // parent directory, which is the platform directory only for a loose ROM: a bundled
            // multi-disc game at Roms/PSX/Game/disc1.cue would land in Saves/Game. Resolving the
            // platform tag here keeps every game on one system in Saves/<tag>, which is where the
            // launcher and save sync both look.
            put("savefile_directory", saveDir.absolutePath)
            put("sort_savefiles_enable", "false")
            put("sort_savefiles_by_content_enable", "false")
            put("state_slot", raSlot.toString())
            // Also in the base config; emitted in the plumbing band too so a tier or custom.cfg
            // cannot turn it off.
            put("savestate_thumbnail_enable", "true")
            put("joypad_autoconfig_dir", paths.configInputAutoconfig.absolutePath)
            // Core options are not RetroArch settings and cannot live in this config, so they are
            // composed into their own file from the same platform and game tiers. global_core_options
            // is what makes RetroArch use core_options_path verbatim instead of deriving a per-core
            // path of its own, which has no platform or game dimension at all.
            put("core_options_path", composeCoreOptions(paths, rom.platformTag, romName, core))
            put("global_core_options", "true")
            putAll(hiddenRaSettingsScreens)
            // settings.language is what Cannoli actually renders in: it defaults to en, and
            // ProvideLocalizedResources applies it whether or not the user ever chose. RetroArch
            // follows the same value so the two never disagree, rather than falling back to its own
            // device detection and putting the settings menu in a different language from the
            // launcher around it.
            RetroArchLanguage.forTag(settings.language)?.let { put("user_language", it.toString()) }
            putAll(cheevos)
            putAll(autoStateOverrides(hardcore, resume, settings.alwaysSaveOnQuit))
        }
        val patched = applyOverrides(preferenceBase, gameOverrides)
        val launchConfig = paths.raLaunchCfg
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }


    /**
     * Screens the in-game menu does not show, expressed the way RetroArch expresses them.
     *
     * DISPLAYLIST_SETTINGS_ALL gates each top-level screen on its own settings_show_ flag, so
     * setting these false means RetroArch never emits the row and the IGM has nothing to
     * refuse. Doing it here rather than filtering by label in the menu keeps the decision on
     * config keys, which are stable, instead of menu label strings, which are not ours and
     * change without notice. Every one of these is something Cannoli owns outright: it writes
     * the config, owns input and achievements, and manages saves, playlists and directories.
     */
    private val hiddenRaSettingsScreens = mapOf(
        "settings_show_saving" to "false",
        "settings_show_configuration" to "false",
        "settings_show_network" to "false",
        "settings_show_playlists" to "false",
        "settings_show_directory" to "false",
        "settings_show_onscreen_display" to "false",
        "settings_show_drivers" to "false",
        "settings_show_user_interface" to "false",
        "settings_show_achievements" to "false",
        "settings_show_accessibility" to "false",
        "settings_show_power_management" to "false",
        "settings_show_user" to "false",
        "settings_show_logging" to "false",
        "settings_show_recording" to "false",
        "settings_show_input" to "false",
        // Frontend behaviour around cores, most of which fights what the launcher owns:
        // systemfiles_in_content_dir_enable, core_info_cache_enable, dummy_on_core_shutdown. The
        // two worth keeping are promoted onto Video. Cutting it also ends the name collision with
        // Cannoli's own Emulator row, which is core options and a different thing entirely.
        "settings_show_core" to "false",
    )

    /**
     * Regenerates the weakest override tier from the launcher's own defaults.
     *
     * Generated rather than hand-written, the same way retroarch_launch.cfg and the composed core
     * options file are, so settings.json stays the single source of truth and a stale or
     * hand-edited global.cfg heals on the next launch. It sits below every tier the in-game menu
     * writes, so anything chosen for a platform or a game silently wins, which is what makes these
     * defaults rather than settings.
     */
    private fun writeGlobalDefaults(paths: CannoliPaths) {
        val defaults = buildMap {
            settings.defaultVideoDriver.takeIf { it.isNotEmpty() }?.let { put("video_driver", it) }
        }
        val target = paths.globalOverrideCfg
        try {
            if (defaults.isEmpty()) {
                target.delete()
                return
            }
            target.parentFile?.mkdirs()
            target.writeText(
                TIER_BANNER + defaults.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n"
            )
        } catch (_: IOException) {
        }
    }

    // Weakest to strongest: platform on this core, then this game on this core. Written out whole
    // every launch, so a key removed from a tier stops applying instead of lingering in the file
    // RetroArch flushed last time.
    private fun composeCoreOptions(
        paths: CannoliPaths,
        tag: String,
        romName: String,
        core: String,
    ): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(readOverrideLayer(paths.systemOverrideOpt(tag, core)))
        merged.putAll(readOverrideLayer(paths.gameOverrideOpt(tag, romName, core)))
        val target = paths.coreOptionsLaunchOpt
        try {
            target.parentFile?.mkdirs()
            target.writeText(merged.entries.joinToString("\n") { "${it.key} = \"${it.value}\"" } + "\n")
        } catch (_: IOException) {
        }
        return target.absolutePath
    }

    private fun applyOverrides(source: String, overrides: Map<String, String>): String =
        RetroArchConfigComposer.compose(source, listOf(overrides))

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun resolveLaunchFile(rom: Rom, extractArchives: Boolean): File? {
        if (extractArchives && ArchiveExtractor.isArchive(rom.path) && !platformConfig.isArcade(rom.platformTag)) {
            return ArchiveExtractor.extract(rom.path, context.cacheDir, rom.path.nameWithoutExtension)
        }
        return rom.path
    }

    /** The stored source that applies to this ROM, game override first. Null means unset. */
    private fun sourceFor(rom: Rom): EmulatorSource? =
        overrideFor(rom)?.source ?: platformConfig.getPlatformChoice(rom.platformTag)?.source

    fun saveStateBasePath(rom: Rom): String {
        val romName = normalizedRomName(rom)
        return CannoliPaths(settings.sdCardRoot).saveStateBase(rom.platformTag, romName).absolutePath
    }

    fun slotOccupancy(rom: Rom): List<Boolean> =
        SaveSlotStore(saveStateBasePath(rom)).occupancy()

    fun findMostRecentSlot(rom: Rom): Int? {
        val slots = SaveSlotStore(saveStateBasePath(rom))
        return (0 until slots.slotCount)
            .filter { slots.exists(it) }
            .maxByOrNull { File(slots.statePath(it)).lastModified() }
    }

    private fun hasSaveState(rom: Rom): Boolean = findMostRecentSlot(rom) != null

    // Nothing stored resolves to the embedded runner, which is the only one always present.
    private fun usesEmbeddedRetroArch(rom: Rom): Boolean =
        (sourceFor(rom) ?: EmulatorSource.Embedded) == EmulatorSource.Embedded

    /** The external RetroArch a choice names, game override first. */
    private fun externalRaPackageFor(rom: Rom): String? =
        overrideFor(rom)?.takeIf { it.source == EmulatorSource.RetroArch }?.appPackage
            ?: platformConfig.getPlatformChoice(rom.platformTag)
                ?.takeIf { it.source == EmulatorSource.RetroArch }?.appPackage

    fun findResumableRoms(roms: List<Rom>): Set<String> {
        val result = mutableSetOf<String>()
        for (rom in roms) {
            if (!hasSaveState(rom)) continue
            // Under hardcore RetroArch refuses to load a state at all, so resume is offering
            // something it cannot deliver. Hiding it here covers every affordance at once:
            // nav.resumableGames is the only thing the legend, the North action, the swapped
            // confirm and the hold-to-pick-a-slot gesture consult.
            if (hardcoreInEffect(cheevosFor(rom))) continue
            // Slots are a Cannoli and libretro concept. A standalone app manages its own saves,
            // so offering Resume for one promises something no external emulator can honour. A
            // separately installed RetroArch manages its own states the same way.
            if (rom.launchTarget is LaunchTarget.RetroArch && usesEmbeddedRetroArch(rom)) {
                result.add(rom.path.absolutePath)
            }
        }
        return result
    }

    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name} target=${rom.launchTarget::class.simpleName}")
        if (launchState.launching) return null
        launchState.launching = true
        launchState.lastLaunched = rom
        val launchFile = resolveLaunchFile(rom, extractArchives = false)
            ?: return errorAndReset(DialogState.LaunchError(context.getString(dev.cannoli.scorza.R.string.launch_error_resolve_file)))

        val gameOverride = overrideFor(rom)
        if (gameOverride?.source == EmulatorSource.Standalone && gameOverride.appPackage != null) {
            val cfg = platformConfig.getAppConfig(rom.platformTag, gameOverride.appPackage)
            return launchResultDialog(
                launchStandalone(rom, launchFile, cfg), rom.platformTag, rom.id
            )
        }
        val overrideRomId = if (gameOverride != null) rom.id else null

        val result = when (val target = rom.launchTarget) {
            is LaunchTarget.RetroArch -> {
                val resolvedCore = gameOverride?.coreId?.ifEmpty { null }
                    ?: platformConfig.getCoreName(rom.platformTag)
                val source = pickSource(
                    gameSource = gameOverride?.source,
                    platformSource = platformConfig.getPlatformChoice(rom.platformTag)?.source,
                    // Any runner that can load the core counts, embedded or external. This only
                    // decides whether to fall back to a standalone app when nothing is stored.
                    raAvailable = {
                        val core = resolvedCore
                        val svc = installedCoreService
                        core != null && svc != null && (
                            core in svc.embeddedCores() ||
                                svc.externalRaCores().any { core in it.value }
                            )
                    },
                    standaloneAvailable = {
                        platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager) != null
                    },
                )
                if (source == EmulatorSource.Standalone) {
                    val cfg = platformConfig.getUserAppMapping(rom.platformTag)
                        ?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                        ?: platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
                        ?: platformConfig.getAppPackage(rom.platformTag)?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                    if (cfg != null) {
                        launchStandalone(rom, launchFile, cfg)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                } else {
                    val core = resolvedCore
                    if (core != null) {
                        debugLog("RetroArch target: core=$core source=$source")
                        // Null source means nothing is stored yet, which resolves to the runner
                        // that is always present.
                        val raPackage = if (source == EmulatorSource.RetroArch) {
                            externalRaPackageFor(rom)
                                ?: return errorAndReset(DialogState.MissingApp(
                                    context.getString(EmulatorSource.RetroArch.labelRes), "",
                                    rom.platformTag, overrideRomId,
                                ))
                        } else null
                        if (raPackage != null) {
                            if (!context.isPackageInstalled(raPackage)) {
                                val appName = try {
                                    val info = context.packageManager.getApplicationInfo(raPackage, 0)
                                    context.packageManager.getApplicationLabel(info).toString()
                                } catch (_: PackageManager.NameNotFoundException) { raPackage }
                                return errorAndReset(DialogState.MissingApp(appName, raPackage, rom.platformTag, overrideRomId))
                            }
                            // The core-install check applies to any package that can report its
                            // cores. It self-skips for one that cannot, since a missing report is
                            // not evidence of a missing core.
                            if (installedCoreService != null
                                && installedCoreService.cacheReady
                                && installedCoreService.canReport(raPackage)
                                && !installedCoreService.hasCoreInPackage(core, raPackage)) {
                                val label = InstalledCoreService.getPackageLabel(raPackage)
                                val coreName = platformConfig.getCoreDisplayName(core)
                                return errorAndReset(DialogState.MissingCore(coreName, label, rom.platformTag, overrideRomId))
                            }
                            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
                            retroArchLauncher.launchRetroArchIntent(launchFile, core, raConfig, raPackage)
                        } else {
                            syncRetroArchConfig(File(settings.sdCardRoot))
                            val launchConfig = buildGameConfig(rom, core) ?: raConfigPath
                            retroArchLauncher.launchRicotta(launchFile, core, launchConfig, buildRicottaIgm(rom))
                        }
                    } else {
                        // App-only platform (no core) that reached here has nothing installed to
                        // run it, so name the app it wants instead of a core it never had.
                        val appPkg = platformConfig.getAppPackage(rom.platformTag)
                        if (appPkg != null && !context.isPackageInstalled(appPkg)) {
                            LaunchResult.AppNotInstalled(appPkg)
                        } else {
                            LaunchResult.CoreNotInstalled("unknown")
                        }
                    }
                }
            }
            is LaunchTarget.EmuLaunch -> {
                emuLauncher.launch(launchFile, target.packageName, target.activityName, target.action)
            }
            is LaunchTarget.ApkLaunch -> {
                val pkg = if (context.isPackageInstalled(target.packageName)) {
                    target.packageName
                } else {
                    platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)?.packageName
                        ?: target.packageName
                }
                if (launchFile.extension != "apk_launch" && launchFile.exists()) {
                    val cfg = platformConfig.getAppConfig(rom.platformTag, pkg)
                    apkLauncher.launchWithRom(pkg, launchFile, cfg)
                } else {
                    apkLauncher.launch(pkg)
                }
            }
        }

        return launchResultDialog(result, rom.platformTag)
    }

    fun launchApp(app: App): DialogState? {
        debugLog("launchApp entered: ${app.type} / ${app.packageName}")
        if (launchState.launching) return null
        launchState.launching = true
        return launchResultDialog(apkLauncher.launch(app.packageName))
    }

    fun resumeRom(rom: Rom): DialogState? = resumeRom(rom, findMostRecentSlot(rom) ?: 0)

    fun resumeRom(rom: Rom, resumeSlot: Int): DialogState? {
        debugLog("resumeRom entered: ${rom.platformTag} / ${rom.path.name} slot=$resumeSlot")
        if (launchState.launching) return null
        launchState.launching = true
        launchState.lastLaunched = rom
        val launchFile = resolveLaunchFile(rom, extractArchives = false)
            ?: run { launchState.launching = false; launchState.lastLaunched = null; return null }
        val gameOverride = overrideFor(rom)
        // Resume had no standalone branch, so a platform mapped to an uninstalled standalone app
        // fell straight through to the RetroArch path and launched the platform default core.
        // Play reported the app as missing; Resume silently ran a different emulator.
        if (sourceFor(rom) == EmulatorSource.Standalone) {
            val cfg = gameOverride?.appPackage?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getUserAppMapping(rom.platformTag)
                    ?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
            return launchResultDialog(
                if (cfg != null) launchStandalone(rom, launchFile, cfg)
                else LaunchResult.CoreNotInstalled("unknown"),
                rom.platformTag, rom.id,
            )
        }
        val core = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
            ?: run { launchState.launching = false; launchState.lastLaunched = null; return null }
        // Resume used to fire and forget: it discarded the LaunchResult and returned without
        // clearing launching, so a failed startActivity never reached onResume and every later
        // launch became a silent no-op. It now runs the same pre-checks and funnel as launchRom.
        val raPackage = if (usesEmbeddedRetroArch(rom)) null else externalRaPackageFor(rom)
        if (raPackage != null) {
            if (!context.isPackageInstalled(raPackage)) {
                return errorAndReset(DialogState.MissingApp(
                    InstalledCoreService.getPackageLabel(raPackage), raPackage,
                    rom.platformTag, rom.id,
                ))
            }
            if (installedCoreService != null
                && installedCoreService.cacheReady
                && installedCoreService.canReport(raPackage)
                && !installedCoreService.hasCoreInPackage(core, raPackage)) {
                return errorAndReset(DialogState.MissingCore(
                    platformConfig.getCoreDisplayName(core),
                    InstalledCoreService.getPackageLabel(raPackage),
                    rom.platformTag, rom.id,
                ))
            }
        }
        val result = if (raPackage == null) {
            syncRetroArchConfig(File(settings.sdCardRoot))
            val launchConfig = buildGameConfig(rom, core, resume = true, slot = resumeSlot) ?: raConfigPath
            retroArchLauncher.launchRicotta(launchFile, core, launchConfig, buildRicottaIgm(rom))
        } else {
            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
            retroArchLauncher.launchRetroArchIntent(launchFile, core, raConfig, raPackage)
        }
        return launchResultDialog(result, rom.platformTag, rom.id)
    }

    private fun errorAndReset(dialog: DialogState): DialogState {
        launchState.launching = false
        launchState.lastLaunched = null
        return dialog
    }

    private fun launchResultDialog(result: LaunchResult, platformTag: String? = null, romId: Long? = null): DialogState? {
        val dialog = toLaunchDialog(result, platformTag, romId)
        if (dialog != null) {
            launchState.launching = false
            launchState.lastLaunched = null
            return dialog
        }
        return DialogState.Launching
    }

    private fun toLaunchDialog(result: LaunchResult, platformTag: String? = null, romId: Long? = null): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName, platformTag = platformTag, romId = romId)
            is LaunchResult.AppNotInstalled -> {
                // getApplicationLabel cannot resolve a package that is not installed, and this
                // branch only fires when it is not, so use the curated label like MissingCore does.
                val appName = InstalledCoreService.getPackageLabel(result.packageName)
                DialogState.MissingApp(appName, result.packageName, platformTag, romId)
            }
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    private val debugSink = dev.cannoli.scorza.util.LogSink(0)
    private val debugFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private var debugSinkRoot: String? = null

    private fun debugLog(message: String) {
        if (!dev.cannoli.scorza.util.LoggingPrefs.session) return
        try {
            val root = settings.sdCardRoot
            if (root != debugSinkRoot) {
                debugSinkRoot = root
                debugSink.open(File(CannoliPaths(root).logsDir, "launch_debug.log"))
            }
            val stamp = synchronized(debugFmt) { debugFmt.format(java.util.Date()) }
            dev.cannoli.scorza.util.LogWriter.write(debugSink, "$stamp $message\n")
        } catch (_: Exception) {}
    }

    private fun normalizedRomName(rom: Rom): String = dev.cannoli.core.RomKey.baseName(rom.path)

    private fun launchStandalone(rom: Rom, launchFile: File, cfg: AppConfig): LaunchResult =
        if (cfg.launchMethod == LaunchMethod.DELFINO) {
            delfinoLauncher.launch(buildDelfinoParams(rom), cfg.packageName)
        } else {
            apkLauncher.launchWithRom(cfg.packageName, launchFile, cfg)
        }

    private fun buildDelfinoParams(rom: Rom): dev.cannoli.igm.DelfinoLaunchParams {
        val igm = buildRicottaIgm(rom)
        val paths = CannoliPaths(settings.sdCardRoot)
        return dev.cannoli.igm.DelfinoLaunchParams(
            romPath = rom.path.absolutePath,
            cannoliRoot = paths.root.absolutePath,
            savesDir = null,
            saveStatesDir = null,
            biosDir = paths.biosFor(rom.platformTag).absolutePath,
            userDir = null,
            gameTitle = rom.displayName,
            platformTag = rom.platformTag,
            igmTriggerKeycodes = igm.igmTriggerKeycodes,
            colors = igm.colors,
            displaySettings = igm.displaySettings,
            inputMapping = igm.inputMapping,
            discPaths = rom.path.takeIf { it.extension.equals("m3u", ignoreCase = true) }
                ?.let(::parseM3uDiscPaths) ?: emptyList(),
        )
    }

    private fun buildRicottaIgm(rom: Rom): RicottaIgm {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val stateBase = paths.saveStateBase(rom.platformTag, romName)
        val batteryDisplay = when (settings.batteryDisplay) {
            dev.cannoli.scorza.settings.BatteryDisplay.HIDE -> BatteryDisplayMode.HIDE
            dev.cannoli.scorza.settings.BatteryDisplay.PERCENT -> BatteryDisplayMode.PERCENT
            dev.cannoli.scorza.settings.BatteryDisplay.ICON -> BatteryDisplayMode.ICON
        }
        val timeFormat = when (settings.timeFormat) {
            dev.cannoli.scorza.settings.TimeFormat.TWELVE_HOUR -> TimeFormatMode.TWELVE_HOUR
            dev.cannoli.scorza.settings.TimeFormat.TWENTY_FOUR_HOUR -> TimeFormatMode.TWENTY_FOUR_HOUR
        }
        return RicottaIgm(
            gameTitle = rom.displayName,
            stateBasePath = stateBase.absolutePath,
            cannoliRoot = paths.root.absolutePath,
            platformTag = rom.platformTag,
            platformName = platformConfig.getDisplayName(rom.platformTag),
            igmTriggerKeycodes = resolveMenuKeycodes(),
            colors = IgmColors(
                highlight = settings.colorHighlight,
                text = settings.colorText,
                highlightText = settings.colorHighlightText,
                accent = settings.colorAccent,
                title = settings.colorTitle,
            ),
            displaySettings = IgmDisplaySettings(
                fontSizeSp = settings.textSize.sp,
                portraitMarginPx = settings.portraitMarginPx,
                geometryWidthPct = settings.screenGeometryWidth,
                geometryHeightPct = settings.screenGeometryHeight,
                geometryXPct = settings.screenGeometryX,
                geometryYPct = settings.screenGeometryY,
                showWifi = settings.showWifi,
                showBluetooth = settings.showBluetooth,
                showVpn = settings.showVpn,
                showClock = settings.showClock,
                batteryDisplay = batteryDisplay,
                timeFormat = timeFormat,
                buttonLabelSet = activeMappingHolder.active.value.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
                confirmButton = activeMappingHolder.active.value.confirmButton(),
            ),
            inputMapping = activeMappingHolder.active.value.toIgmInputMapping(),
            romBaseName = romName,
            hardcoreInEffect = hardcoreInEffect(cheevosFor(rom)),
            curatedSettings = settings.igmSettingsMode == IgmSettingsMode.CURATED,
        )
    }

    // Keycodes bound to BTN_MENU in the active mapping open the Cannoli IGM in ricotta,
    // mirroring how the launcher's own in-game menu opens. Falls back to the platform
    // default (BACK + BUTTON_MODE) when no mapping is active.
    private fun resolveMenuKeycodes(): List<Int> =
        activeMappingHolder.active.value
            ?.bindings
            ?.get(dev.cannoli.scorza.input.CanonicalButton.BTN_MENU)
            ?.filterIsInstance<dev.cannoli.scorza.input.InputBinding.Button>()
            ?.map { it.keyCode }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_BUTTON_MODE,
            )

    companion object {
        // Both branches state all five session/account/mode keys outright rather than leaving any
        // out, because the base config is not the only thing buildGameConfig composes over: a
        // system, game or custom override cfg is user- or Kitchen-writable and can carry a cheevos
        // block of its own. An unstated key leaves that value live, which is how a logged out
        // player could still be launching with someone's account, token and hardcore. Hardcore is
        // stated because RetroArch defaults it to true, so enabling cheevos without it would put
        // every player into hardcore. Cannoli never launches with a password at all, so that one
        // is always empty.
        fun cheevosOverrides(
            username: String,
            token: String,
            hardcore: Boolean,
            forceSoftcore: Boolean,
        ): Map<String, String> {
            if (username.isEmpty() || token.isEmpty()) return mapOf(
                "cheevos_enable" to "false",
                "cheevos_hardcore_mode_enable" to "false",
                "cheevos_username" to "",
                "cheevos_token" to "",
                "cheevos_password" to "",
            )
            return mapOf(
                "cheevos_enable" to "true",
                "cheevos_username" to username,
                "cheevos_token" to token,
                "cheevos_password" to "",
                "cheevos_hardcore_mode_enable" to (hardcore && !forceSoftcore).toString(),
                "cheevos_verbose_enable" to "true",
                "cheevos_badges_enable" to "false",
            )
        }

        // Read from the emission rather than the settings so the launch config, the resume
        // affordance and the IGM's save state rows can never disagree.
        fun hardcoreInEffect(cheevos: Map<String, String>): Boolean =
            cheevos["cheevos_hardcore_mode_enable"] == "true"

        /**
         * The auto-slot keys for one launch. They are computed per launch from the resume and
         * slot state, so they belong in the plumbing band, not the regenerated base config.
     *
         * Hardcore writes neither: the auto slot's only consumer is resume, which is hidden
         * under hardcore. config.def.h defaults both to false, so an absent key is off and
         * bridge.savesOnQuit reads back false, skipping the IGM's auto-slot rotation too.
         */
        fun autoStateOverrides(
            hardcore: Boolean,
            resume: Boolean,
            alwaysSaveOnQuit: Boolean,
        ): Map<String, String> {
            if (hardcore) return emptyMap()
            return buildMap {
                put("savestate_auto_save", if (alwaysSaveOnQuit) "true" else "false")
                if (resume) put("savestate_auto_load", "true")
            }
        }

        // Null means no stored preference anywhere, so the caller falls through to its embedded
        // core probe and then RetroArch, which is what the pre-identity code did implicitly.
        // The availability probes are lazy because a stored choice decides the answer on its
        // own, and probing the filesystem to reach a conclusion already known is wasted work.
        /**
         * The Cannoli-owned default config, regenerated on every launch as the base the override tiers compose over.
     *
         * RetroArch derives its save and state directories by appending to the configured ones,
         * and both sort flags default to on. Cannoli's layout is one directory per system, so the
         * content-directory level is wanted and the core level never is: leaving
         * `sort_savefiles_enable` at its default gives `Saves/GBA/mGBA` instead of `Saves/GBA`.
         * Both flags are therefore stated outright rather than left to the defaults.
         */
        fun buildMinimalConfig(rootPath: String) = buildString {
            appendLine("savefile_directory = \"$rootPath/Saves\"")
            appendLine("savestate_directory = \"$rootPath/Save States\"")
            appendLine("sort_savefiles_by_content_enable = \"true\"")
            appendLine("sort_savefiles_enable = \"false\"")
            // buildGameConfig pins the state directory exactly and turns both of these off. These
            // only decide where states land if that per-game write fails and the base is launched
            // on its own, so they mirror the savefile rule rather than contradicting it.
            appendLine("sort_savestates_by_content_enable = \"true\"")
            appendLine("sort_savestates_enable = \"false\"")
            appendLine("savestate_file_compression = \"false\"")
            // RetroArch defaults this off everywhere but x86_64, and the slot thumbnails are the
            // whole point of the save and load rows.
            appendLine("savestate_thumbnail_enable = \"true\"")
            appendLine("config_save_on_exit = \"false\"")
            // Cannoli composes the override tiers itself; RetroArch's own auto-override loading
            // would layer a second, uncontrolled copy on top of what was just composed.
            appendLine("auto_overrides_enable = \"false\"")
            appendLine("video_font_enable = \"false\"")
            appendLine("assets_directory = \"$rootPath/Config/Assets\"")
            // RetroArch appends the joypad driver name to this, so it scans Autoconfig/android,
            // which is where the seeder writes the cfgs Cannoli and RetroArch now share.
            appendLine("joypad_autoconfig_dir = \"$rootPath/Config/Input/Autoconfig\"")
        }

        fun pickSource(
            gameSource: EmulatorSource?,
            platformSource: EmulatorSource?,
            raAvailable: () -> Boolean,
            standaloneAvailable: () -> Boolean,
        ): EmulatorSource? {
            val stored = gameSource ?: platformSource
            if (stored != null) return stored
            if (!raAvailable() && standaloneAvailable()) return EmulatorSource.Standalone
            return null
        }

        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = File(context.applicationInfo.sourceDir).lastModified().toString()
            if (versionFile.exists() && versionFile.readText() == currentVersion) return coresDir.absolutePath
            // Extraction only ever overwrites what the current APK bundles; it must never delete
            // a core, since a name dropped from the bundle (v2 is moving to download-on-first-run)
            // is not obsolete. The user may still be relying on it, whether it got there from an
            // older bundle or a manual download, and this has no way to tell those apart.
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apkZip ->
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val prefix = "lib/$abi/"
                for (entry in apkZip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith("_libretro_android.so")) continue
                    val name = entry.name.removePrefix(prefix)
                    val dst = File(coresDir, name)
                    apkZip.getInputStream(entry).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                }
            }
            versionFile.writeText(currentVersion)
            return coresDir.absolutePath
        }
    }
}
