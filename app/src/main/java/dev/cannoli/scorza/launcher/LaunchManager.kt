package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.SaveSlotManager
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.util.ArchiveExtractor
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
    private val installedCoreService: InstalledCoreService? = null,
) {
    private var raConfigPath: String? = null
    @Volatile var launching = false

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
            put("sort_savestates_by_content_enable", "false")
            put("state_slot", raSlot.toString())
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
            put("savestate_file_compression", "false")
            put("savestate_block_format", "false")
            put("savestate_thumbnail_enable", "true")
            put("savestate_auto_save", "true")
            put("config_save_on_exit", "false")
            put("video_font_enable", "false")

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

    fun createTempM3u(rom: Rom): File {
        val m3uDir = File(context.cacheDir, "m3u")
        m3uDir.mkdirs()
        val m3uFile = File(m3uDir, "${rom.displayName}.m3u")
        m3uFile.writeText(checkNotNull(rom.discFiles).joinToString("\n") { it.absolutePath } + "\n")
        return m3uFile
    }

    fun resolveLaunchFile(rom: Rom): File? {
        if (rom.discFiles != null) return createTempM3u(rom)
        if (ArchiveExtractor.isArchive(rom.path) && !platformConfig.isArcade(rom.platformTag)) {
            return ArchiveExtractor.extract(rom.path, context.cacheDir)
        }
        return rom.path
    }

    fun findEmbeddedCore(coreName: String): String? {
        val soName = "${coreName}_android.so"
        val coreFile = File(context.filesDir, "cores/$soName")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    fun getEmbeddedCorePath(rom: Rom): String? {
        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        if (gameOverride?.appPackage != null) return null
        val target = rom.launchTarget
        if (target is LaunchTarget.Embedded) return target.corePath
        if (target !is LaunchTarget.RetroArch) return null
        val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag) ?: return null
        val runnerPref = gameOverride?.runner ?: platformConfig.getRunnerPreference(rom.platformTag)
        if (runnerPref == "RetroArch" || runnerPref == "RicottaArch") return null
        return findEmbeddedCore(core)
    }

    fun findMostRecentSlot(rom: Rom): Int? {
        val romName = normalizedRomName(rom)
        val stateBase = CannoliPaths(settings.sdCardRoot).saveStateBase(rom.platformTag, romName)
        val slotManager = SaveSlotManager(stateBase.absolutePath)
        return slotManager.slots
            .filter { File(slotManager.statePath(it)).exists() }
            .maxByOrNull { File(slotManager.statePath(it)).lastModified() }
            ?.index
    }

    private fun hasSaveState(rom: Rom): Boolean {
        val romName = normalizedRomName(rom)
        val stateDir = CannoliPaths(settings.sdCardRoot).saveStateDir(rom.platformTag, romName)
        if (!stateDir.exists()) return false
        return stateDir.listFiles()?.any {
            it.name.startsWith("$romName.state") && !it.name.endsWith(".png")
        } ?: false
    }

    fun findResumableRoms(roms: List<Rom>): Set<String> {
        val result = mutableSetOf<String>()
        for (rom in roms) {
            if (!hasSaveState(rom)) continue
            val target = rom.launchTarget
            val embedded = target is LaunchTarget.Embedded || getEmbeddedCorePath(rom) != null
            if (embedded || (target is LaunchTarget.RetroArch && !settings.retroArchDiyMode)) {
                result.add(rom.path.absolutePath)
            }
        }
        return result
    }

    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name} target=${rom.launchTarget::class.simpleName}")
        if (launching) return null
        launching = true
        val launchFile = resolveLaunchFile(rom)
            ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))

        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        if (gameOverride?.appPackage != null) {
            val cfg = platformConfig.getAppConfig(rom.platformTag, gameOverride.appPackage)
            val appConfig = ApkLauncher.AppLaunchConfig(
                activityName = cfg.activity,
                action = cfg.action,
                data = cfg.data,
                extraKey = cfg.extraKey,
                extraKind = cfg.extraKind
            )
            return launchResultDialog(apkLauncher.launchWithRom(gameOverride.appPackage, launchFile, appConfig))
        }

        val result = when (val target = rom.launchTarget) {
            is LaunchTarget.RetroArch -> {
                var runnerPref = gameOverride?.runner ?: platformConfig.getRunnerPreference(rom.platformTag)
                if (runnerPref == null) {
                    val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag)
                    val embeddedAvailable = core?.let { findEmbeddedCore(it) != null } ?: false
                    val raAvailable = core != null && installedCoreService?.installedCores?.any { it.value.contains(core) } == true
                    if (!embeddedAvailable && !raAvailable
                        && platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager) != null) {
                        runnerPref = "Standalone"
                    }
                }
                if (runnerPref == "App" || runnerPref == "Standalone") {
                    val cfg = platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
                        ?: platformConfig.getAppPackage(rom.platformTag)?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                    if (cfg != null) {
                        val appConfig = ApkLauncher.AppLaunchConfig(
                            activityName = cfg.activity,
                            action = cfg.action,
                            data = cfg.data,
                            extraKey = cfg.extraKey,
                            extraKind = cfg.extraKind
                        )
                        apkLauncher.launchWithRom(cfg.packageName, launchFile, appConfig)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                } else {
                    val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag)
                    if (core != null) {
                        if (runnerPref != "RetroArch" && runnerPref != "RicottaArch") {
                            val embeddedCorePath = findEmbeddedCore(core)
                            debugLog("RetroArch target: core=$core runnerPref=$runnerPref embeddedCorePath=$embeddedCorePath")
                            if (embeddedCorePath != null) {
                                launchEmbedded(rom.copy(path = launchFile), embeddedCorePath, originalRomPath = rom.path.absolutePath)
                                return null
                            }
                        }
                        val raPackage = gameOverride?.raPackage
                            ?: platformConfig.getPackage(rom.platformTag)
                        if (raPackage != null && installedCoreService != null) {
                            if (!context.isPackageInstalled(raPackage)) {
                                val appName = try {
                                    val info = context.packageManager.getApplicationInfo(raPackage, 0)
                                    context.packageManager.getApplicationLabel(info).toString()
                                } catch (_: PackageManager.NameNotFoundException) { raPackage }
                                return errorAndReset(DialogState.MissingApp(appName, raPackage))
                            }
                            if (installedCoreService.cacheReady
                                && raPackage !in installedCoreService.unresponsivePackages
                                && !installedCoreService.hasCoreInPackage(core, raPackage)) {
                                val label = InstalledCoreService.getPackageLabel(raPackage)
                                return errorAndReset(DialogState.MissingCore("$core not found in $label"))
                            }
                        }
                        if (settings.retroArchDiyMode) {
                            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
                            retroArchLauncher.launch(launchFile, core, raConfig, raPackage, buildIGMExtras(rom))
                        } else {
                            syncRetroArchConfig(File(settings.sdCardRoot))
                            val launchConfig = buildGameConfig(rom) ?: raConfigPath
                            retroArchLauncher.launch(launchFile, core, launchConfig, raPackage, buildIGMExtras(rom))
                        }
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
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
                    val appConfig = ApkLauncher.AppLaunchConfig(
                        activityName = cfg.activity,
                        action = cfg.action,
                        data = cfg.data,
                        extraKey = cfg.extraKey,
                        extraKind = cfg.extraKind
                    )
                    apkLauncher.launchWithRom(pkg, launchFile, appConfig)
                } else {
                    apkLauncher.launch(pkg)
                }
            }
            is LaunchTarget.Embedded -> {
                launchEmbedded(rom.copy(path = launchFile), target.corePath, originalRomPath = rom.path.absolutePath)
                return null
            }
        }

        return launchResultDialog(result)
    }

    fun launchApp(app: App): DialogState? {
        debugLog("launchApp entered: ${app.type} / ${app.packageName}")
        if (launching) return null
        launching = true
        return launchResultDialog(apkLauncher.launch(app.packageName))
    }

    fun resumeRom(rom: Rom): DialogState? {
        debugLog("resumeRom entered: ${rom.platformTag} / ${rom.path.name}")
        if (launching) return null
        launching = true
        val resumeSlot = findMostRecentSlot(rom) ?: 0
        val launchFile = resolveLaunchFile(rom) ?: run { launching = false; return null }
        val embeddedCorePath = getEmbeddedCorePath(rom)
        if (embeddedCorePath != null) {
            launchEmbedded(rom.copy(path = launchFile), embeddedCorePath, resumeSlot, originalRomPath = rom.path.absolutePath)
            return null
        }
        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag) ?: run { launching = false; return null }
        val raPackage = gameOverride?.raPackage ?: platformConfig.getPackage(rom.platformTag)
        if (settings.retroArchDiyMode) {
            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
            retroArchLauncher.launch(launchFile, core, raConfig, raPackage)
        } else {
            syncRetroArchConfig(File(settings.sdCardRoot))
            val launchConfig = buildGameConfig(rom, resume = true, slot = resumeSlot) ?: raConfigPath
            retroArchLauncher.launch(launchFile, core, launchConfig, raPackage)
        }
        return null
    }

    private fun errorAndReset(dialog: DialogState): DialogState {
        launching = false
        return dialog
    }

    private fun launchResultDialog(result: LaunchResult): DialogState? {
        val dialog = toLaunchDialog(result)
        if (dialog != null) launching = false
        return dialog
    }

    private fun toLaunchDialog(result: LaunchResult): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName)
            is LaunchResult.AppNotInstalled -> {
                val appName = try {
                    val info = context.packageManager.getApplicationInfo(result.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    result.packageName
                }
                DialogState.MissingApp(appName, result.packageName)
            }
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    private fun debugLog(message: String) {
        if (!settings.debugLogging) return
        try {
            val dir = CannoliPaths(settings.sdCardRoot).logsDir
            dir.mkdirs()
            val f = File(dir, "launch_debug.log")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $message\n")
        } catch (_: Exception) {}
    }

    fun launchEmbedded(rom: Rom, corePath: String, resumeSlot: Int = -1, originalRomPath: String? = null) {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val saveDir = paths.savesFor(rom.platformTag)
        saveDir.mkdirs()
        val stateDir = paths.saveStateDir(rom.platformTag, romName)
        stateDir.mkdirs()

        val args = LaunchArgs(
            gameTitle = rom.displayName,
            corePath = corePath,
            romPath = rom.path.absolutePath,
            originalRomPath = originalRomPath?.takeIf { it != rom.path.absolutePath },
            sramPath = File(saveDir, "$romName.srm").absolutePath,
            statePath = File(stateDir, "$romName.state").absolutePath,
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
            font = settings.font,
            debugLogging = settings.debugLogging,
            raUsername = settings.raUsername,
            raToken = settings.raToken,
            raPassword = settings.raPassword,
            raGameId = rom.raGameId,
            resumeSlot = resumeSlot,
        )
        val intent = args.writeTo(Intent(context, LibretroActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        context.startActivity(intent, opts)
    }

    private fun normalizedRomName(rom: Rom): String =
        Normalizer.normalize(rom.path.nameWithoutExtension, Normalizer.Form.NFC)

    private fun buildIGMExtras(rom: Rom): IGMExtras {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val stateBase = paths.saveStateBase(rom.platformTag, romName)
        return IGMExtras(
            gameTitle = rom.displayName,
            stateBasePath = stateBase.absolutePath,
            cannoliRoot = paths.root.absolutePath,
            platformTag = rom.platformTag,
            colorHighlight = settings.colorHighlight,
            colorText = settings.colorText,
            colorHighlightText = settings.colorHighlightText,
            colorAccent = settings.colorAccent,
            colorTitle = settings.colorTitle
        )
    }

    companion object {
        private const val CONFIG_VERSION = 5

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
