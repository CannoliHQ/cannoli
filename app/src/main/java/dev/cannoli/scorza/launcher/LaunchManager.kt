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
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.SaveSlotManager
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

    fun syncRetroArchConfig(root: File) {
        val raDir = CannoliPaths(root).configRetroArch
        raDir.mkdirs()
        val localConfig = File(raDir, "retroarch.cfg")
        val hashFile = File(raDir, ".ra_config_hash")

        val raPackage = settings.retroArchPackage
        val sourceConfig = File("/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg")

        if (!sourceConfig.exists()) {
            if (!localConfig.exists()) {
                localConfig.writeText(buildMinimalConfig(root.absolutePath))
            }
            raConfigPath = localConfig.absolutePath
            return
        }

        val sourceBytes = try { sourceConfig.readBytes() } catch (_: IOException) {
            if (!localConfig.exists()) localConfig.writeText(buildMinimalConfig(root.absolutePath))
            raConfigPath = localConfig.absolutePath
            return
        }
        val sourceHash = sha256(sourceBytes, "$CONFIG_VERSION:${settings.raUsername}:${settings.raToken}".toByteArray())
        val storedHash = if (hashFile.exists()) try { hashFile.readText().trim() } catch (_: IOException) { "" } else ""

        if (sourceHash != storedHash || !localConfig.exists()) {
            val patched = patchRetroArchConfig(String(sourceBytes), root.absolutePath)
            localConfig.writeText(patched)
            hashFile.writeText(sourceHash)
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
        val biosDir = paths.biosFor(rom.platformTag)
        biosDir.mkdirs()
        val raSlot = if (slot > 0) slot - 1 else 0
        val gameOverrides = buildMap {
            put("system_directory", biosDir.absolutePath)
            put("savestate_directory", stateDir.absolutePath)
            put("sort_savestates_enable", "false")
            put("sort_savestates_by_content_enable", "false")
            put("state_slot", raSlot.toString())
            // RetroArch's own auto-save is how ricotta saves on quit; the built-in runner does the
            // equivalent explicitly in LibretroActivity. Both write the Auto slot, so both gate on
            // the same setting. This lives here, not in the base config, because the base is
            // hash-gated and would not pick up a toggle until CONFIG_VERSION moved.
            put("savestate_auto_save", if (settings.alwaysSaveOnQuit) "true" else "false")
            if (resume) {
                put("savestate_auto_load", "true")
            }
        }
        val patched = applyOverrides(baseConfig, gameOverrides)
        val launchConfig = paths.raLaunchCfg
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }

    private fun buildMinimalConfig(rootPath: String) = buildString {
        appendLine("savefile_directory = \"$rootPath/Saves\"")
        appendLine("savestate_directory = \"$rootPath/Save States\"")
        appendLine("sort_savefiles_by_content_enable = \"true\"")
        appendLine("savestate_file_compression = \"false\"")
        appendLine("config_save_on_exit = \"false\"")
        appendLine("video_font_enable = \"false\"")
        appendLine("assets_directory = \"$rootPath/Config/Assets\"")
    }

    private fun patchRetroArchConfig(source: String, rootPath: String): String {
        val raUser = settings.raUsername
        val raToken = settings.raToken
        val overrides = buildMap {
            put("savefile_directory", "$rootPath/Saves")
            put("savestate_directory", "$rootPath/Save States")
            put("screenshot_directory", "$rootPath/Media/Screenshots")
            put("recording_output_directory", "$rootPath/Media/Recordings")
            put("sort_savefiles_by_content_enable", "true")
            // RetroArch defaults this to true and would nest the core name under the content
            // directory, giving Saves/GBA/mGBA instead of the Saves/GBA the internal runner writes.
            put("sort_savefiles_enable", "false")
            put("savestate_file_compression", "false")
            put("savestate_block_format", "false")
            put("savestate_thumbnail_enable", "true")
            put("config_save_on_exit", "false")
            put("video_font_enable", "false")
            put("auto_overrides_enable", "true")

            // TODO come back to this at a later date
//            put("assets_directory", "$rootPath/Config/Assets")

            if (raUser.isNotEmpty() && raToken.isNotEmpty()) {
                put("cheevos_enable", "true")
                put("cheevos_username", raUser)
                put("cheevos_token", raToken)
            }
        }
        return applyOverrides(source, overrides)
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

    fun findEmbeddedCore(coreName: String): String? {
        val soName = "${coreName}_android.so"
        val coreFile = File(context.filesDir, "cores/$soName")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    /** The stored source that applies to this ROM, game override first. Null means unset. */
    private fun sourceFor(rom: Rom): EmulatorSource? =
        overrideFor(rom)?.source ?: platformConfig.getPlatformChoice(rom.platformTag)?.source

    fun getEmbeddedCorePath(rom: Rom): String? {
        val target = rom.launchTarget
        if (target is LaunchTarget.Embedded) return target.corePath
        if (target !is LaunchTarget.RetroArch) return null
        val gameOverride = overrideFor(rom)
        val source = sourceFor(rom)
        // Only an Internal selection may use the bundled core. A platform mapped to a standalone
        // app used to fall through here, so A launched the app while Resume launched the core.
        if (source != null && source != EmulatorSource.Internal) return null
        val core = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag) ?: return null
        return findEmbeddedCore(core)
    }

    fun saveStateBasePath(rom: Rom): String {
        val romName = normalizedRomName(rom)
        return CannoliPaths(settings.sdCardRoot).saveStateBase(rom.platformTag, romName).absolutePath
    }

    fun slotOccupancy(rom: Rom): List<Boolean> {
        val slotManager = SaveSlotManager(saveStateBasePath(rom))
        return (0..10).map { i ->
            slotManager.slots.firstOrNull { it.index == i }
                ?.let { File(slotManager.statePath(it)).exists() } ?: false
        }
    }

    fun findMostRecentSlot(rom: Rom): Int? {
        val slotManager = SaveSlotManager(saveStateBasePath(rom))
        return slotManager.slots
            .filter { File(slotManager.statePath(it)).exists() }
            .maxByOrNull { File(slotManager.statePath(it)).lastModified() }
            ?.index
    }

    private fun hasSaveState(rom: Rom): Boolean = findMostRecentSlot(rom) != null

    fun findResumableRoms(roms: List<Rom>): Set<String> {
        val result = mutableSetOf<String>()
        for (rom in roms) {
            if (!hasSaveState(rom)) continue
            // Slots are a Cannoli and libretro concept. A standalone app manages its own saves,
            // so offering Resume for one promises something no external emulator can honour.
            if (sourceFor(rom) == EmulatorSource.Standalone) continue
            val target = rom.launchTarget
            val embedded = target is LaunchTarget.Embedded || getEmbeddedCorePath(rom) != null
            if (embedded || (target is LaunchTarget.RetroArch && RetroArchLauncher.isRicotta(settings.retroArchPackage))) {
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
                    embeddedAvailable = { resolvedCore?.let { findEmbeddedCore(it) != null } ?: false },
                    // configuredCores, not installedCores: a core present only in a RetroArch the
                    // user is not using must not suppress the standalone fallback.
                    raAvailable = {
                        resolvedCore != null &&
                            installedCoreService?.configuredCores()?.any { it.value.contains(resolvedCore) } == true
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
                        if (source != EmulatorSource.RetroArch) {
                            val embeddedCorePath = findEmbeddedCore(core)
                            debugLog("RetroArch target: core=$core source=$source embeddedCorePath=$embeddedCorePath")
                            if (embeddedCorePath != null) {
                                val embeddedFile = resolveLaunchFile(rom, extractArchives = true)
                                    ?: return errorAndReset(DialogState.LaunchError(context.getString(dev.cannoli.scorza.R.string.launch_error_extract)))
                                return launchEmbedded(rom.copy(path = embeddedFile), embeddedCorePath, originalRomPath = rom.path.absolutePath)
                            }
                        }
                        val raPackage = settings.retroArchPackage
                        if (!context.isPackageInstalled(raPackage)) {
                            val appName = try {
                                val info = context.packageManager.getApplicationInfo(raPackage, 0)
                                context.packageManager.getApplicationLabel(info).toString()
                            } catch (_: PackageManager.NameNotFoundException) { raPackage }
                            return errorAndReset(DialogState.MissingApp(appName, raPackage, rom.platformTag, overrideRomId))
                        }
                        // Core-install check applies to RicottaArch and to RetroArch installs
                        // that report their cores; it self-skips for installs that cannot
                        // (unresponsivePackages), since the user owns those.
                        if (installedCoreService != null
                            && installedCoreService.cacheReady
                            && raPackage !in installedCoreService.unresponsivePackages
                            && !installedCoreService.hasCoreInPackage(core, raPackage)) {
                            val label = InstalledCoreService.getPackageLabel(raPackage)
                            val coreName = platformConfig.getCoreDisplayName(core)
                            return errorAndReset(DialogState.MissingCore(coreName, label, rom.platformTag, overrideRomId))
                        }
                        if (RetroArchLauncher.isRicotta(raPackage)) {
                            syncRetroArchConfig(File(settings.sdCardRoot))
                            val launchConfig = buildGameConfig(rom) ?: raConfigPath
                            retroArchLauncher.launchRicotta(launchFile, core, launchConfig, raPackage, buildRicottaIgm(rom))
                        } else {
                            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
                            retroArchLauncher.launchRetroArchIntent(launchFile, core, raConfig, raPackage)
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
            is LaunchTarget.Embedded -> {
                val embeddedFile = resolveLaunchFile(rom, extractArchives = true)
                    ?: return errorAndReset(DialogState.LaunchError(context.getString(dev.cannoli.scorza.R.string.launch_error_extract)))
                return launchEmbedded(rom.copy(path = embeddedFile), target.corePath, originalRomPath = rom.path.absolutePath)
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
        val embeddedCorePath = getEmbeddedCorePath(rom)
        val launchFile = resolveLaunchFile(rom, extractArchives = embeddedCorePath != null)
            ?: run { launchState.launching = false; launchState.lastLaunched = null; return null }
        if (embeddedCorePath != null) {
            return launchEmbedded(rom.copy(path = launchFile), embeddedCorePath, resumeSlot, originalRomPath = rom.path.absolutePath)
        }
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
        val raPackage = settings.retroArchPackage
        // Resume used to fire and forget: it discarded the LaunchResult and returned without
        // clearing launching, so a failed startActivity never reached onResume and every later
        // launch became a silent no-op. It now runs the same pre-checks and funnel as launchRom.
        if (!context.isPackageInstalled(raPackage)) {
            return errorAndReset(DialogState.MissingApp(
                InstalledCoreService.getPackageLabel(raPackage), raPackage,
                rom.platformTag, rom.id,
            ))
        }
        if (installedCoreService != null
            && installedCoreService.cacheReady
            && raPackage !in installedCoreService.unresponsivePackages
            && !installedCoreService.hasCoreInPackage(core, raPackage)) {
            return errorAndReset(DialogState.MissingCore(
                platformConfig.getCoreDisplayName(core),
                InstalledCoreService.getPackageLabel(raPackage),
                rom.platformTag, rom.id,
            ))
        }
        val result = if (RetroArchLauncher.isRicotta(raPackage)) {
            syncRetroArchConfig(File(settings.sdCardRoot))
            val launchConfig = buildGameConfig(rom, resume = true, slot = resumeSlot) ?: raConfigPath
            retroArchLauncher.launchRicotta(launchFile, core, launchConfig, raPackage, buildRicottaIgm(rom))
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
        if (dialog != null) launchState.launching = false
        return dialog
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

    private fun debugLog(message: String) {
        if (!dev.cannoli.scorza.util.LoggingPrefs.session) return
        try {
            val dir = CannoliPaths(settings.sdCardRoot).logsDir
            dir.mkdirs()
            val f = File(dir, "launch_debug.log")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $message\n")
        } catch (_: Exception) {}
    }

    fun launchEmbedded(rom: Rom, corePath: String, resumeSlot: Int = -1, originalRomPath: String? = null): DialogState? {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)

        originalRomPath?.let { orig ->
            val archive = File(orig)
            if (archive.absolutePath != rom.path.absolutePath && ArchiveExtractor.isArchive(archive)) {
                SaveIdentityMigrator(File(settings.sdCardRoot), atomicRename).migrateOnLaunch(rom.platformTag, romName, archive)
            }
        }

        val saveDir = paths.savesFor(rom.platformTag)
        saveDir.mkdirs()
        val stateBase = paths.saveStateBase(rom.platformTag, romName)
        stateBase.parentFile?.mkdirs()

        val args = LaunchArgs(
            gameTitle = rom.displayName,
            corePath = corePath,
            romPath = rom.path.absolutePath,
            originalRomPath = originalRomPath?.takeIf { it != rom.path.absolutePath },
            sramPath = paths.sramFile(rom.platformTag, romName).absolutePath,
            statePath = stateBase.absolutePath,
            systemDir = paths.biosFor(rom.platformTag).absolutePath,
            saveDir = saveDir.absolutePath,
            platformTag = rom.platformTag,
            platformName = platformConfig.getDisplayName(rom.platformTag),
            cannoliRoot = paths.root.absolutePath,
            colorHighlight = settings.colorHighlight,
            colorText = settings.colorText,
            colorHighlightText = settings.colorHighlightText,
            colorAccent = settings.colorAccent,
            colorTitle = settings.colorTitle,
            colorBackground = settings.colorBackground,
            colorStatusBar = settings.colorStatusBar,
            font = settings.font,
            debugLogging = settings.loggingSession,
            raUsername = settings.raUsername,
            raToken = settings.raToken,
            raPassword = settings.raPassword,
            raGameId = rom.raGameId,
            romId = rom.id,
            resumeSlot = resumeSlot,
        )
        val intent = args.writeTo(Intent(context, LibretroActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        return try {
            context.startActivity(intent, opts)
            null
        } catch (t: Throwable) {
            errorAndReset(DialogState.LaunchError(context.getString(dev.cannoli.scorza.R.string.launch_error_generic)))
        }
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
        fun pickSource(
            gameSource: EmulatorSource?,
            platformSource: EmulatorSource?,
            embeddedAvailable: () -> Boolean,
            raAvailable: () -> Boolean,
            standaloneAvailable: () -> Boolean,
        ): EmulatorSource? {
            val stored = gameSource ?: platformSource
            if (stored != null) return stored
            if (!embeddedAvailable() && !raAvailable() && standaloneAvailable()) return EmulatorSource.Standalone
            return null
        }

        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = File(context.applicationInfo.sourceDir).lastModified().toString()
            if (versionFile.exists() && versionFile.readText() == currentVersion) return coresDir.absolutePath
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
            coresDir.listFiles()?.forEach { f ->
                if (f.name.endsWith("_libretro_android.so") && f.name !in extracted) f.delete()
            }
            versionFile.writeText(currentVersion)
            return coresDir.absolutePath
        }
    }
}
