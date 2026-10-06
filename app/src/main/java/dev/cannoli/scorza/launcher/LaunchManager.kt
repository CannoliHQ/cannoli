package dev.cannoli.scorza.launcher

import android.content.Context
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
import dev.cannoli.core.shader.ShaderIndex
import dev.cannoli.scorza.model.App
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

private const val ASSET_SHADERS = "shaders"
private const val BUNDLED_STAMP = ".bundled_version"

private const val TIER_BANNER =
    "# DO NOT EDIT - Cannoli writes this from your menu choices. Your own keys go in custom.cfg\n"

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val retroArchLauncher: RetroArchLauncher,
    private val apkLauncher: ApkLauncher,
    private val delfinoLauncher: DelfinoLauncher,
    private val launchState: LaunchState,
    private val activeMappingHolder: dev.cannoli.scorza.input.runtime.ActiveMappingHolder,
    private val installedCoreService: InstalledCoreService? = null,
    private val gameOverrides: dev.cannoli.scorza.db.GameOverrideStore? = null,
    private val globalOverrides: dev.cannoli.scorza.settings.GlobalOverridesManager? = null,
) {
    private fun overrideFor(rom: Rom): dev.cannoli.scorza.config.EmulatorChoice? =
        gameOverrides?.get(rom.id)

    private fun cheevosFor(rom: Rom): Map<String, String> = cheevosOverrides(
        username = settings.raUsername,
        token = settings.raToken,
        hardcore = rom.raHardcore ?: settings.raHardcore,
        forceSoftcore = rom.raGameId != null,
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

    fun syncBundledShaders(root: File) {
        val dest = CannoliPaths(root).shadersDir
        val stamp = File(dest, BUNDLED_STAMP)
        val digest = assetDigest(ASSET_SHADERS) ?: return
        if (try { stamp.readText().trim() } catch (_: IOException) { null } == digest) return
        if (!copyAssetTree(ASSET_SHADERS, dest)) return
        try {
            stamp.writeText(digest)
        } catch (_: IOException) {
        }
    }

    private fun assetDigest(assetPath: String): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        digestAssetTree(assetPath, md)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun digestAssetTree(assetPath: String, md: MessageDigest) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            md.update(assetPath.toByteArray())
            context.assets.open(assetPath).use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
        } else {
            children.sorted().forEach { digestAssetTree("$assetPath/$it", md) }
        }
    }

    fun ensureShaderIndex(root: File) {
        val dir = CannoliPaths(root).shadersDir
        if (!dir.isDirectory) return
        if (ShaderIndex.file(dir).isFile) return
        ShaderIndex.build(dir)
    }

    private fun copyAssetTree(assetPath: String, dest: File): Boolean {
        val children = try { context.assets.list(assetPath) } catch (_: IOException) { null }
        return if (children.isNullOrEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                true
            } catch (_: IOException) {
                false
            }
        } else {
            dest.mkdirs()
            children.all { copyAssetTree("$assetPath/$it", File(dest, it)) }
        }
    }

    fun writeRunnerConfig(root: File) {
        val raDir = CannoliPaths(root).configRetroArch
        raDir.mkdirs()
        val localConfig = File(raDir, "retroarch.cfg")
        localConfig.writeText(
            "# DO NOT EDIT - Cannoli regenerates this. Your own keys go in custom.cfg\n" +
                buildMinimalConfig(root.absolutePath)
        )
        raConfigPath = localConfig.absolutePath
    }

    private fun readOverrideLayer(file: File): Map<String, String> =
        if (!file.exists()) emptyMap()
        else try { RetroArchConfigComposer.parse(file.readText()) } catch (_: IOException) { emptyMap() }

    private fun buildGameConfig(rom: Rom, core: String, resume: Boolean = false, slot: Int = 0): String? {
        val base = raConfigPath ?: return null
        val baseConfig = try { File(base).readText() } catch (_: IOException) { return null }
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        writeGlobalDefaults(paths)
        val preferenceBase = RetroArchConfigComposer.compose(
            baseConfig,
            listOf(
                readOverrideLayer(paths.globalOverrideCfg),
                stickDpadDefaults(rom.platformTag),
                readOverrideLayer(paths.systemSharedCfg(rom.platformTag)),
                readOverrideLayer(paths.systemOverrideCfg(rom.platformTag, core)),
                readOverrideLayer(paths.gameSharedCfg(rom.platformTag, romName)),
                readOverrideLayer(paths.gameOverrideCfg(rom.platformTag, romName, core)),
                readOverrideLayer(paths.customCfg),
            ),
        )
        val stateDir = paths.saveStateDir(rom.platformTag, romName, core)
        stateDir.mkdirs()
        dev.cannoli.scorza.saves.SaveMigration(paths).migrateGame(rom.platformTag, romName)
        val saveDir = if (dev.cannoli.scorza.saves.SharedSaveRoots.isShared(rom.platformTag)) {
            paths.savesFor(rom.platformTag)
        } else {
            paths.saveDirFor(rom.platformTag, romName)
        }
        saveDir.mkdirs()
        val biosDir = prepareBios(rom.platformTag, paths.biosFor(rom.platformTag))
        val raSlot = if (slot > 0) slot - 1 else 0
        val cheevos = cheevosFor(rom)
        val hardcore = hardcoreInEffect(cheevos)
        val gameOverrides = buildMap {
            put("system_directory", biosDir.absolutePath)
            put("savestate_directory", stateDir.absolutePath)
            put("sort_savestates_enable", "false")
            put("sort_savestates_by_content_enable", "false")
            put("savefile_directory", saveDir.absolutePath)
            put("sort_savefiles_enable", "false")
            put("sort_savefiles_by_content_enable", "false")
            put("save_file_compression", "false")
            put("state_slot", raSlot.toString())
            put("savestate_thumbnail_enable", "true")
            put("joypad_autoconfig_dir", paths.configInputAutoconfig.absolutePath)
            put("video_shader_dir", paths.shadersDir.absolutePath)
            put("core_options_path", composeCoreOptions(paths, rom.platformTag, romName, core))
            put("global_core_options", "true")
            put("load_dummy_on_core_shutdown", "false")
            putAll(hiddenRaSettingsScreens)
            RetroArchLanguage.forTag(settings.language)?.let { put("user_language", it.toString()) }
            putAll(cheevos)
            putAll(autoStateOverrides(hardcore, resume, settings.alwaysSaveOnQuit))
        }
        val patched = applyOverrides(preferenceBase, gameOverrides)
        val launchConfig = paths.raLaunchCfg
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }


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
        "settings_show_core" to "false",
    )

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

    private fun stickDpadDefaults(tag: String): Map<String, String> =
        if (platformConfig.forcesStickDpad(tag)) {
            (1..16).associate { "input_player${it}_analog_dpad_mode" to "3" }
        } else {
            emptyMap()
        }

    private fun composeCoreOptions(
        paths: CannoliPaths,
        tag: String,
        romName: String,
        core: String,
    ): String {
        val merged = ShippedCoreOptions.compose(
            core = core,
            glyphStyle = activeMappingHolder.active.value?.glyphStyle,
            platform = readOverrideLayer(paths.systemOverrideOpt(tag, core)),
            game = readOverrideLayer(paths.gameOverrideOpt(tag, romName, core)),
        )
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

    fun resolveLaunchFile(rom: Rom, extractArchives: Boolean): File? {
        if (extractArchives && ArchiveExtractor.isArchive(rom.path) && !platformConfig.isArcade(rom.platformTag)) {
            return ArchiveExtractor.extract(rom.path, context.cacheDir, rom.path.nameWithoutExtension)
        }
        return rom.path
    }

    fun coreForStates(rom: Rom): String? =
        overrideFor(rom)?.coreId?.ifEmpty { null } ?: platformConfig.getCoreName(rom.platformTag)

    private fun sourceFor(rom: Rom): EmulatorSource? =
        overrideFor(rom)?.source ?: platformConfig.getPlatformChoice(rom.platformTag)?.source

    fun saveStateBasePath(rom: Rom): String {
        val romName = normalizedRomName(rom)
        val core = coreForStates(rom) ?: return CannoliPaths(settings.sdCardRoot)
            .saveStateGameDir(rom.platformTag, romName).absolutePath
        return CannoliPaths(settings.sdCardRoot)
            .saveStateBase(rom.platformTag, romName, core).absolutePath
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

    private fun usesEmbeddedRetroArch(rom: Rom): Boolean =
        (sourceFor(rom) ?: EmulatorSource.Embedded) == EmulatorSource.Embedded

    fun findResumableRoms(roms: List<Rom>): Set<String> {
        val result = mutableSetOf<String>()
        for (rom in roms) {
            if (!hasSaveState(rom)) continue
            if (hardcoreInEffect(cheevosFor(rom))) continue
            if (usesEmbeddedRetroArch(rom)) {
                result.add(rom.path.absolutePath)
            }
        }
        return result
    }

    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name}")
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

        val resolvedCore = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
        val source = pickSource(
            gameSource = gameOverride?.source,
            platformSource = platformConfig.getPlatformChoice(rom.platformTag)?.source,
            raAvailable = {
                val core = resolvedCore
                val svc = installedCoreService
                core != null && svc != null && core in svc.embeddedCores()
            },
            standaloneAvailable = {
                platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager) != null
            },
        )
        val result = if (source == EmulatorSource.Standalone) {
            val cfg = platformConfig.getUserAppMapping(rom.platformTag)
                ?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
                ?: platformConfig.getAppPackage(rom.platformTag)?.let { platformConfig.getAppConfig(rom.platformTag, it) }
            if (cfg != null) {
                launchStandalone(rom, launchFile, cfg)
            } else {
                LaunchResult.NoEmulatorSet
            }
        } else {
            val core = resolvedCore
            if (core != null) {
                debugLog("RetroArch target: core=$core source=$source")
                if (installedCoreService != null && !coreIsComplete(core, rom.platformTag)) {
                    return errorAndReset(DialogState.MissingCore(
                        platformConfig.getCoreDisplayName(core),
                        platformTag = rom.platformTag,
                        romId = overrideRomId,
                        coreId = core,
                        platformName = platformConfig.getDisplayName(rom.platformTag),
                    ))
                }
                val badExt = unsupportedExtension(launchFile, core)
                if (badExt != null) {
                    return errorAndReset(DialogState.UnsupportedContent(
                        platformName = platformConfig.getDisplayName(rom.platformTag),
                        coreName = platformConfig.getCoreDisplayName(core),
                        extension = badExt,
                        supported = platformConfig.coreExtensions(core),
                        platformTag = rom.platformTag,
                        romId = overrideRomId,
                    ))
                }
                val absentBios = missingRequiredBios(rom.platformTag, core)
                if (absentBios.isNotEmpty()) {
                    return errorAndReset(DialogState.MissingBios(
                        platformName = platformConfig.getDisplayName(rom.platformTag),
                        files = absentBios,
                        platformTag = rom.platformTag,
                        romId = overrideRomId,
                    ))
                }
                writeRunnerConfig(File(settings.sdCardRoot))
                val launchConfig = buildGameConfig(rom, core) ?: raConfigPath
                retroArchLauncher.launchRicotta(launchFile, core, launchConfig, buildRicottaIgm(rom))
            } else {
                val appPkg = platformConfig.getAppPackage(rom.platformTag)
                if (appPkg != null && !context.isPackageInstalled(appPkg)) {
                    LaunchResult.AppNotInstalled(appPkg)
                } else {
                    LaunchResult.NoEmulatorSet
                }
            }
        }

        return launchResultDialog(result, rom.platformTag, overrideRomId)
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
        if (sourceFor(rom) == EmulatorSource.Standalone) {
            val cfg = gameOverride?.appPackage?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getUserAppMapping(rom.platformTag)
                    ?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
            return launchResultDialog(
                if (cfg != null) launchStandalone(rom, launchFile, cfg)
                else LaunchResult.NoEmulatorSet,
                rom.platformTag, rom.id,
            )
        }
        val core = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
            ?: run { launchState.launching = false; launchState.lastLaunched = null; return null }
        if (installedCoreService != null && !coreIsComplete(core, rom.platformTag)) {
            return errorAndReset(DialogState.MissingCore(
                platformConfig.getCoreDisplayName(core),
                platformTag = rom.platformTag, romId = rom.id, coreId = core,
                platformName = platformConfig.getDisplayName(rom.platformTag),
            ))
        }
        val badExt = unsupportedExtension(launchFile, core)
        if (badExt != null) {
            return errorAndReset(DialogState.UnsupportedContent(
                platformName = platformConfig.getDisplayName(rom.platformTag),
                coreName = platformConfig.getCoreDisplayName(core),
                extension = badExt,
                supported = platformConfig.coreExtensions(core),
                platformTag = rom.platformTag,
                romId = rom.id,
            ))
        }
        val absentBios = missingRequiredBios(rom.platformTag, core)
        if (absentBios.isNotEmpty()) {
            return errorAndReset(DialogState.MissingBios(
                platformName = platformConfig.getDisplayName(rom.platformTag),
                files = absentBios,
                platformTag = rom.platformTag,
                romId = rom.id,
            ))
        }
        writeRunnerConfig(File(settings.sdCardRoot))
        val launchConfig = buildGameConfig(rom, core, resume = true, slot = resumeSlot) ?: raConfigPath
        val result = retroArchLauncher.launchRicotta(launchFile, core, launchConfig, buildRicottaIgm(rom))
        return launchResultDialog(result, rom.platformTag, rom.id)
    }

    private fun unsupportedExtension(launchFile: File, core: String): String? =
        ContentSupport.unsupported(launchFile, platformConfig.coreExtensions(core))

    private fun coreIsComplete(core: String, tag: String): Boolean {
        val service = installedCoreService ?: return true
        if (core !in service.embeddedCores()) return false
        val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
        return SystemFiles.remoteSetsPresent(context.assets, core, tag, biosDir)
    }

    private fun prepareBios(tag: String, dir: File): File {
        dir.mkdirs()
        SystemFiles.ensureBundled(context.assets, tag, dir, apkStamp(context))
        return dir
    }

    private fun missingRequiredBios(tag: String, core: String): List<String> {
        val biosDir = prepareBios(tag, CannoliPaths(File(settings.sdCardRoot)).biosFor(tag))
        return platformConfig.getFirmwareStatus(tag, core, biosDir)
            .filterNot { it.satisfied }
            .map { req ->
                when (req) {
                    is dev.cannoli.scorza.config.FirmwareRequirement.Single -> File(req.entry.path).name
                    is dev.cannoli.scorza.config.FirmwareRequirement.AnyOf ->
                        req.options.joinToString(" or ") { File(it.first.path).name }
                }
            }
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
            is LaunchResult.AppNotInstalled -> {
                val appName = InstalledCoreService.getPackageLabel(result.packageName)
                DialogState.MissingApp(
                    appName, result.packageName, platformTag, romId,
                    platformName = platformTag?.let { platformConfig.getDisplayName(it) } ?: "",
                )
            }
            LaunchResult.NoEmulatorSet -> DialogState.NoEmulatorSet(
                platformName = platformTag?.let { platformConfig.getDisplayName(it) } ?: "",
                platformTag = platformTag,
                romId = romId,
            )
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
        val stateBase = paths.saveStateBase(
            rom.platformTag, romName, coreForStates(rom).orEmpty()
        )
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
            shortcuts = globalOverrides?.readShortcuts()?.filterValues { it.isNotEmpty() }.orEmpty(),
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

        fun hardcoreInEffect(cheevos: Map<String, String>): Boolean =
            cheevos["cheevos_hardcore_mode_enable"] == "true"

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

        fun buildMinimalConfig(rootPath: String) = buildString {
            appendLine("savefile_directory = \"$rootPath/Saves\"")
            appendLine("savestate_directory = \"$rootPath/Save States\"")
            appendLine("sort_savefiles_by_content_enable = \"true\"")
            appendLine("sort_savefiles_enable = \"false\"")
            appendLine("sort_savestates_by_content_enable = \"true\"")
            appendLine("sort_savestates_enable = \"false\"")
            appendLine("savestate_file_compression = \"false\"")
            appendLine("save_file_compression = \"false\"")
            appendLine("savestate_thumbnail_enable = \"true\"")
            appendLine("config_save_on_exit = \"false\"")
            appendLine("bundle_assets_extract_enable = \"false\"")
            appendLine("auto_overrides_enable = \"false\"")
            appendLine("auto_remaps_enable = \"false\"")
            appendLine("video_font_enable = \"false\"")
            appendLine("menu_driver = \"rgui\"")
            appendLine("assets_directory = \"$rootPath/Config/Assets\"")
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

        fun apkStamp(context: Context): String =
            context.applicationInfo?.sourceDir?.let { File(it).lastModified().toString() } ?: ""

        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = apkStamp(context)
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
