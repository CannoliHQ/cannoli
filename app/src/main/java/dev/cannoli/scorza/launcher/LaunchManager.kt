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
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.util.ArchiveExtractor
import dev.cannoli.scorza.util.parseM3uDiscPaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer

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
     * RetroArch to copy from, and Cannoli owns the embedded runner's config outright, so the
     * config is simply generated when absent.
     */
    fun syncRetroArchConfig(root: File) {
        val raDir = CannoliPaths(root).configRetroArch
        raDir.mkdirs()
        val localConfig = File(raDir, "retroarch.cfg")
        if (!localConfig.exists()) {
            localConfig.writeText(buildMinimalConfig(root.absolutePath))
        }
        raConfigPath = localConfig.absolutePath
    }

    private fun buildGameConfig(rom: Rom, resume: Boolean = false, slot: Int = 0): String? {
        val base = raConfigPath ?: return null
        val baseConfig = try { File(base).readText() } catch (_: IOException) { return null }
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val stateDir = paths.saveStateDir(rom.platformTag, romName)
        stateDir.mkdirs()
        val saveDir = paths.savesFor(rom.platformTag)
        saveDir.mkdirs()
        val biosDir = paths.biosFor(rom.platformTag)
        biosDir.mkdirs()
        val raSlot = if (slot > 0) slot - 1 else 0
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
            // RetroArch's own auto-save is how ricotta saves on quit; the built-in runner does the
            // equivalent explicitly in LibretroActivity. Both write the Auto slot, so both gate on
            // the same setting. This lives here, not in the base config, because the base is
            // hash-gated and would not pick up a toggle until CONFIG_VERSION moved.
            put("savestate_auto_save", if (settings.alwaysSaveOnQuit) "true" else "false")
            // Also in the base config, and repeated here for the same reason: an install made
            // before this existed already has a base config and will never be handed a new one.
            put("savestate_thumbnail_enable", "true")
            put("joypad_autoconfig_dir", paths.configInputAutoconfig.absolutePath)
            if (resume) {
                put("savestate_auto_load", "true")
            }
        }
        val patched = applyOverrides(baseConfig, gameOverrides)
        val launchConfig = paths.raLaunchCfg
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }


    private fun applyOverrides(source: String, overrides: Map<String, String>): String {
        val applied = mutableSetOf<String>()
        val lines = source.lines().map { line ->
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore('=').trim().removePrefix("# ")
            if (key in overrides) {
                applied.add(key)
                "$key = \"${overrides[key]}\""
            } else line
        }.toMutableList()
        for ((key, value) in overrides) {
            if (key !in applied) lines.add("$key = \"$value\"")
        }
        return lines.joinToString("\n")
    }

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
                                    EmulatorSource.RetroArch.displayName, "",
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
                            val launchConfig = buildGameConfig(rom) ?: raConfigPath
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
            val launchConfig = buildGameConfig(rom, resume = true, slot = resumeSlot) ?: raConfigPath
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

    private fun normalizedRomName(rom: Rom): String =
        Normalizer.normalize(rom.path.nameWithoutExtension, Normalizer.Form.NFC)

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
        private const val CONFIG_VERSION = 7

        // Null means no stored preference anywhere, so the caller falls through to its embedded
        // core probe and then RetroArch, which is what the pre-identity code did implicitly.
        // The availability probes are lazy because a stored choice decides the answer on its
        // own, and probing the filesystem to reach a conclusion already known is wasted work.
        /**
         * The base config the embedded RetroArch loads when nothing else has written one.
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

        // Line 0 of the stamp is the version, the rest is what was extracted. A downloaded core is
        // never in it, so it is never removed.
        internal fun staleBundledCores(stamp: List<String>, extractedNow: Set<String>): List<String> =
            stamp.drop(1).filter { it.isNotEmpty() && it !in extractedNow }

        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = File(context.applicationInfo.sourceDir).lastModified().toString()
            val stamp = if (versionFile.exists()) versionFile.readLines() else emptyList()
            if (stamp.firstOrNull() == currentVersion) return coresDir.absolutePath
            val extracted = mutableSetOf<String>()
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apkZip ->
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val prefix = "lib/$abi/"
                for (entry in apkZip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith("_libretro_android.so")) continue
                    val name = entry.name.removePrefix(prefix)
                    val dst = File(coresDir, name)
                    apkZip.getInputStream(entry).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                    extracted.add(name)
                }
            }
            staleBundledCores(stamp, extracted).forEach { File(coresDir, it).delete() }
            versionFile.writeText((listOf(currentVersion) + extracted.sorted()).joinToString("\n"))
            return coresDir.absolutePath
        }
    }
}
