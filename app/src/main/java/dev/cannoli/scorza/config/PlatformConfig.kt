package dev.cannoli.scorza.config

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import dev.cannoli.core.IniData
import dev.cannoli.core.IniParser
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

data class GameCoreOverride(val coreId: String = "", val runner: String? = null, val appPackage: String? = null)

class PlatformConfig(
    private val cannoliRootProvider: () -> File,
    private val assets: AssetManager,
    private val coreInfo: CoreInfoRepository? = null,
    private val nativeLibDir: String? = null,
    // Resolved string passed in because this class has no Context; defaulted for tests.
    private val emptyOverrideLabel: String = "(empty override)",
) {

    constructor(
        cannoliRoot: File,
        assets: AssetManager,
        coreInfo: CoreInfoRepository? = null,
        nativeLibDir: String? = null,
    ) : this({ cannoliRoot }, assets, coreInfo, nativeLibDir)

    private var defaultCores = mapOf<String, String>()
    private var defaultPlatformNames = mapOf<String, String>()
    private var defaultApps = mapOf<String, List<AppConfig>>()
    private var arcadePlatforms = setOf<String>()

    init {
        // Bundled asset defaults are always available regardless of storage permission, so seed
        // them at construction. load() will be called after permission to overlay user INI.
        loadPlatformsAsset()
    }

    private fun loadPlatformsAsset() {
        val json = JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })
        val cores = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val apps = mutableMapOf<String, List<AppConfig>>()
        val arcade = mutableSetOf<String>()
        for (tag in json.keys()) {
            val entry = json.getJSONObject(tag)
            entry.optString("name", "").takeIf { it.isNotEmpty() }?.let { names[tag] = it }
            entry.optString("core", "").takeIf { it.isNotEmpty() }?.let { cores[tag] = it }
            if (entry.optBoolean("arcade")) arcade.add(tag)
            val appArray = entry.optJSONArray("app")
            val list = mutableListOf<AppConfig>()
            if (appArray != null) {
                for (i in 0 until appArray.length()) {
                    val item = appArray.get(i)
                    val obj = when (item) {
                        is String -> JSONObject().put("package", item)
                        is JSONObject -> item
                        else -> throw IllegalArgumentException("platforms.json[$tag].app[$i]: expected string or object")
                    }
                    list.add(parseAppConfig(obj))
                }
            } else {
                entry.optString("app", "").takeIf { it.isNotEmpty() }?.let {
                    list.add(AppConfig(packageName = it))
                }
            }
            if (list.isNotEmpty()) apps[tag] = list
        }
        defaultCores = cores
        defaultPlatformNames = names
        defaultApps = apps
        arcadePlatforms = arcade
    }

    private var ini: IniData = IniData(emptyMap())
    private var userCores: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var userRunners: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var userApps: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var gameOverrides: MutableMap<String, GameCoreOverride> = java.util.concurrent.ConcurrentHashMap()
    private val paths: CannoliPaths get() = CannoliPaths(cannoliRootProvider())
    private val coresFile get() = paths.coresJson

    private fun romsTagDir(tag: String, romsDir: File = paths.romsDir): File {
        val direct = File(romsDir, tag)
        if (direct.exists()) return direct
        return romsDir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) } ?: direct
    }

    fun load() {
        loadPlatformsAsset()
        val configFile = paths.platformsIni
        if (!configFile.exists()) {
            writeDefaultIni(configFile)
        }
        ini = IniParser.parse(configFile)
        loadCoreMappings()
    }

    private fun loadCoreMappings() {
        userCores.clear()
        userRunners.clear()
        userApps.clear()
        gameOverrides.clear()
        if (!coresFile.exists()) return
        try {
            val json = JSONObject(coresFile.readText())
            val cores = json.optJSONObject("cores")
            if (cores != null) for (key in cores.keys()) userCores[key] = cores.getString(key)
            val runners = json.optJSONObject("runners")
            if (runners != null) for (key in runners.keys()) userRunners[key] = runners.getString(key)
            val apps = json.optJSONObject("apps")
            if (apps != null) for (key in apps.keys()) userApps[key] = apps.getString(key)
            val overrides = json.optJSONObject("gameOverrides")
            if (overrides != null) {
                for (path in overrides.keys()) {
                    val obj = overrides.getJSONObject(path)
                    gameOverrides[path] = GameCoreOverride(
                        coreId = obj.optString("core", ""),
                        runner = obj.optString("runner", "").ifEmpty { null },
                        appPackage = obj.optString("app", "").ifEmpty { null }
                    )
                }
            }
        } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
    }

    fun reloadCoreMappings() {
        loadCoreMappings()
    }

    fun saveCoreMappings() {
        val json = JSONObject()
        val cores = JSONObject()
        for ((tag, core) in userCores) cores.put(tag, core)
        json.put("cores", cores)
        if (userRunners.isNotEmpty()) {
            val runners = JSONObject()
            for ((tag, runner) in userRunners) runners.put(tag, runner)
            json.put("runners", runners)
        }
        if (userApps.isNotEmpty()) {
            val apps = JSONObject()
            for ((tag, app) in userApps) apps.put(tag, app)
            json.put("apps", apps)
        }
        if (gameOverrides.isNotEmpty()) {
            val overrides = JSONObject()
            for ((path, ov) in gameOverrides) {
                val obj = JSONObject()
                if (ov.appPackage != null) {
                    obj.put("app", ov.appPackage)
                } else {
                    obj.put("core", ov.coreId)
                    if (ov.runner != null) obj.put("runner", ov.runner)
                }
                overrides.put(path, obj)
            }
            json.put("gameOverrides", overrides)
        }
        coresFile.parentFile?.mkdirs()
        coresFile.writeText(json.toString(2))
    }

    fun getCoreMapping(tag: String): String {
        val upper = tag.uppercase()
        return userCores[tag] ?: defaultCores[upper] ?: ""
    }

    fun setCoreMapping(tag: String, core: String, runner: String? = null) {
        val defaultCore = defaultCores[tag.uppercase()]
        if (core.isBlank() || core == defaultCore) {
            userCores.remove(tag)
        } else {
            userCores[tag] = core
        }
        if (runner != null) {
            userRunners[tag] = runner
        } else {
            userRunners.remove(tag)
        }
        userApps.remove(tag)
    }

    fun getRunnerPreference(tag: String): String? = userRunners[tag]

    fun getGameOverride(gamePath: String): GameCoreOverride? = gameOverrides[gamePath]

    fun snapshotGameOverrides(): Map<String, GameCoreOverride> = gameOverrides.toMap()

    fun getPlatformOverrides(tag: String, romsDir: File = paths.romsDir): List<Pair<String, String>> {
        val tagDir = romsTagDir(tag, romsDir).absolutePath
        return gameOverrides.entries
            .filter { (path, _) -> path.startsWith(tagDir) }
            .map { (path, ov) ->
                val label = when {
                    ov.appPackage != null -> "Standalone: ${knownAppLabels[ov.appPackage] ?: ov.appPackage}"
                    ov.coreId.isNotEmpty() -> "${ov.runner ?: "RetroArch"}: ${getCoreDisplayName(ov.coreId)}"
                    else -> emptyOverrideLabel
                }
                path to label
            }
            .sortedBy { it.first }
    }

    fun resetPlatformMapping(tag: String) {
        userCores.remove(tag)
        userRunners.remove(tag)
        userApps.remove(tag)
        saveCoreMappings()
    }

    fun clearGameOverride(gamePath: String) {
        gameOverrides.remove(gamePath)
        saveCoreMappings()
    }

    fun hasUserMapping(tag: String): Boolean =
        userCores.containsKey(tag) || userRunners.containsKey(tag) || userApps.containsKey(tag)

    fun setGameOverride(gamePath: String, coreId: String?, runner: String?) {
        if (coreId == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(coreId, runner)
        }
        saveCoreMappings()
    }

    fun isKnownTag(tag: String): Boolean {
        val upper = tag.uppercase()
        return upper in defaultPlatformNames || upper in ini.getSection("platforms")
    }

    fun isArcade(tag: String): Boolean = tag.uppercase() in arcadePlatforms

    fun getAllTags(): Set<String> = defaultPlatformNames.keys + ini.getSection("platforms").keys

    fun getAppPackage(tag: String): String? = userApps[tag] ?: defaultApps[tag.uppercase()]?.firstOrNull()?.packageName

    fun getAppOptions(tag: String): List<AppConfig> = defaultApps[tag.uppercase()] ?: emptyList()

    fun getAppConfig(tag: String, packageName: String): AppConfig {
        val configs = getAppOptions(tag)
        return configs.firstOrNull { it.packageName == packageName } ?: AppConfig(packageName)
    }

    fun getFirstInstalledApp(tag: String, pm: PackageManager): AppConfig? {
        return getAppOptions(tag).firstOrNull { pm.isPackageInstalled(it.packageName) }
    }

    fun setAppMapping(tag: String, appPackage: String?) {
        if (appPackage == null) {
            userApps.remove(tag)
            userRunners.remove(tag)
        } else {
            userApps[tag] = appPackage
            userRunners[tag] = "Standalone"
        }
        userCores.remove(tag)
    }

    fun setGameAppOverride(gamePath: String, appPackage: String?) {
        if (appPackage == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(appPackage = appPackage)
        }
        saveCoreMappings()
    }

    fun getCoreDisplayName(coreId: String): String {
        return coreInfo?.getDisplayName(coreId) ?: coreId
    }

    fun getMissingFirmware(coreId: String, biosDir: File): List<FirmwareEntry> {
        return coreInfo?.getMissingFirmware(coreId, biosDir) ?: emptyList()
    }

    fun getFirmwareStatus(coreId: String, biosDir: File): List<Pair<FirmwareEntry, Boolean>> {
        val all = coreInfo?.getFirmwareFor(coreId) ?: emptyList()
        return all.map { it to File(biosDir, it.path).exists() }
    }

    fun getRunnerLabel(tag: String, coreId: String, installedRaCores: Map<String, Set<String>> = emptyMap()): String {
        if (File(romsTagDir(tag), ".emu_launch").exists()) return "External"
        val override = userRunners[tag]
        if (override == "App") return "Standalone"
        if (override != null) return override
        if (nativeLibDir != null && File(nativeLibDir, "${coreId}_android.so").exists()) return "Internal"
        return installedRaCores.keys.firstOrNull()?.let { InstalledCoreService.getPackageLabel(it) } ?: "RetroArch"
    }

    fun getDetailedMappings(
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unresponsivePackages: Set<String> = emptySet()
    ): List<dev.cannoli.scorza.ui.screens.EmulatorMappingEntry> {
        val tags = (defaultCores.keys + defaultApps.keys + userCores.keys + userApps.keys)
        return tags.map { tag ->
            val app = getAppPackage(tag)
            val coreId = getCoreMapping(tag)
            val runner = userRunners[tag]
            val installedApp: String? = when {
                pm == null -> app
                else -> getAppOptions(tag).firstOrNull { pm.isPackageInstalled(it.packageName) }?.packageName
            }
            val userPickedStandalone = userApps[tag] != null || runner == "Standalone"

            if (userPickedStandalone) {
                val resolved = userApps[tag] ?: installedApp
                if (resolved != null) {
                    val appName = pm?.let { resolveAppLabel(it, resolved) } ?: (knownAppLabels[resolved] ?: resolved)
                    val installed = pm == null || pm.isPackageInstalled(resolved)
                    dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone",
                        status = if (installed) EmulatorMappingStatus.READY else EmulatorMappingStatus.NOT_INSTALLED
                    )
                } else {
                    dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = "Needs setup", runnerLabel = "",
                        status = EmulatorMappingStatus.NEEDS_SETUP
                    )
                }
            } else if (app != null && coreId.isBlank()) {
                if (installedApp != null) {
                    val appName = pm?.let { resolveAppLabel(it, installedApp) } ?: (knownAppLabels[installedApp] ?: installedApp)
                    dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone",
                        status = EmulatorMappingStatus.READY
                    )
                } else {
                    val appName = pm?.let { resolveAppLabel(it, app) } ?: (knownAppLabels[app] ?: app)
                    dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone",
                        status = EmulatorMappingStatus.NOT_INSTALLED
                    )
                }
            } else if (coreId.isBlank()) {
                dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                    tag = tag, platformName = getDisplayName(tag),
                    coreDisplayName = "Needs setup", runnerLabel = "",
                    status = EmulatorMappingStatus.NEEDS_SETUP
                )
            } else {
                val resolvedRunner = getRunnerLabel(tag, coreId, installedRaCores)
                val status = coreStatus(tag, coreId, resolvedRunner, installedRaCores, embeddedCoresDir, unresponsivePackages)
                // "Missing" is a confirmed absence (internal .so or new-RA report). "Unknown"
                // means RetroArch cannot report its cores (older RA), so we cannot claim the
                // core is missing - show it as picked rather than false-flagging it.
                val mappingStatus = if (status == "Missing") EmulatorMappingStatus.NOT_INSTALLED else EmulatorMappingStatus.READY
                dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                    tag = tag, platformName = getDisplayName(tag),
                    coreDisplayName = getCoreDisplayName(coreId),
                    runnerLabel = resolvedRunner,
                    status = mappingStatus
                )
            }
        }.sortedNatural { it.platformName }
    }

    private fun coreStatus(
        tag: String, coreId: String, runner: String,
        installedRaCores: Map<String, Set<String>>,
        embeddedCoresDir: String?,
        unresponsivePackages: Set<String>
    ): String {
        if (runner == "Internal") {
            val dir = embeddedCoresDir ?: nativeLibDir ?: return "Missing"
            return if (File(dir, "${coreId}_android.so").exists()) "Present" else "Missing"
        }
        if (runner == "External") return "Present"
        if (installedRaCores.any { it.value.contains(coreId) }) return "Present"
        if (unresponsivePackages.isNotEmpty()) return "Unknown"
        return "Missing"
    }

    fun availableSources(tag: String, embeddedCoresDir: String? = null): List<EmulatorSource> {
        val upper = tag.uppercase()
        val candidateCores = buildSet {
            defaultCores[upper]?.let { add(it) }
            coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
        }
        val embeddedDir = embeddedCoresDir ?: nativeLibDir
        val hasInternal = embeddedDir != null && candidateCores.any {
            File(embeddedDir, "${it}_android.so").exists()
        }
        val hasRaCandidates = candidateCores.isNotEmpty()
        val hasStandaloneCandidates = getAppOptions(tag).isNotEmpty()
        return buildList {
            if (hasInternal) add(EmulatorSource.Internal)
            if (hasRaCandidates) add(EmulatorSource.RetroArch)
            if (hasStandaloneCandidates) add(EmulatorSource.Standalone)
        }
    }

    fun emulatorOptionsForSource(
        tag: String,
        source: EmulatorSource,
        includeAll: Boolean,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        pm: PackageManager? = null,
    ): List<dev.cannoli.scorza.ui.screens.EmulatorPickerOption> {
        val upper = tag.uppercase()
        val candidateCoreIds = buildSet {
            defaultCores[upper]?.let { add(it) }
            coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
        }
        val embeddedDir = embeddedCoresDir ?: nativeLibDir
        return when (source) {
            EmulatorSource.Internal -> candidateCoreIds.mapNotNull { coreId ->
                val present = embeddedDir != null && File(embeddedDir, "${coreId}_android.so").exists()
                when {
                    present -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId), runnerLabel = "Internal"
                    )
                    includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId), runnerLabel = "Internal", available = false
                    )
                    else -> null
                }
            }
            EmulatorSource.RetroArch -> candidateCoreIds.mapNotNull { coreId ->
                val pkg = installedRaCores.entries.firstOrNull { coreId in it.value }?.key
                when {
                    pkg != null -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId),
                        runnerLabel = InstalledCoreService.getPackageLabel(pkg)
                    )
                    includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId),
                        runnerLabel = "RetroArch", available = false
                    )
                    else -> null
                }
            }
            EmulatorSource.Standalone -> getAppOptions(tag).mapNotNull { cfg ->
                val installed = pm?.isPackageInstalled(cfg.packageName) ?: true
                val appName = pm?.let { resolveAppLabel(it, cfg.packageName) } ?: (knownAppLabels[cfg.packageName] ?: cfg.packageName)
                when {
                    installed -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = "", displayName = appName, runnerLabel = "Standalone", appPackage = cfg.packageName
                    )
                    includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = "", displayName = appName, runnerLabel = "Standalone", appPackage = cfg.packageName, available = false
                    )
                    else -> null
                }
            }
        }
    }

    fun isRetroArchUnresponsive(
        tag: String,
        installedRaCores: Map<String, Set<String>>,
        unresponsivePackages: Set<String>
    ): Boolean {
        val upper = tag.uppercase()
        val candidateCoreIds = buildSet {
            defaultCores[upper]?.let { add(it) }
            coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
        }
        val anyReports = installedRaCores.any { (pkg, cores) ->
            pkg !in unresponsivePackages && candidateCoreIds.any { it in cores }
        }
        return !anyReports && unresponsivePackages.isNotEmpty()
    }

    fun getCorePickerOptions(
        tag: String,
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unresponsivePackages: Set<String> = emptySet()
    ): List<dev.cannoli.scorza.ui.screens.EmulatorPickerOption> {
        val options = mutableListOf<dev.cannoli.scorza.ui.screens.EmulatorPickerOption>()

        val candidateCoreIds = mutableSetOf<String>()
        val upper = tag.uppercase()
        defaultCores[upper]?.let { candidateCoreIds.add(it) }
        coreInfo?.getCoresForTag(tag)?.forEach { candidateCoreIds.add(it.id) }

        for (coreId in candidateCoreIds) {
            val displayName = getCoreDisplayName(coreId)

            val checkDir = embeddedCoresDir ?: nativeLibDir
            if (checkDir != null && File(checkDir, "${coreId}_android.so").exists()) {
                options.add(dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                    coreId = coreId, displayName = displayName, runnerLabel = "Internal"
                ))
            }

            for ((pkg, cores) in installedRaCores) {
                if (coreId in cores) {
                    options.add(dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = displayName,
                        runnerLabel = InstalledCoreService.getPackageLabel(pkg)
                    ))
                }
            }

            for (pkg in unresponsivePackages) {
                if (installedRaCores.containsKey(pkg)) continue
                val label = InstalledCoreService.getPackageLabel(pkg)
                options.add(dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                    coreId = coreId, displayName = displayName,
                    runnerLabel = "$label (Unknown)"
                ))
            }
        }

        val appPackages = getAppOptions(tag)
        for (config in appPackages) {
            val pkg = config.packageName
            val appName = pm?.let { resolveAppLabel(it, pkg) } ?: (knownAppLabels[pkg] ?: pkg)
            val installed = pm?.isPackageInstalled(pkg) ?: true
            options.add(dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                coreId = "", displayName = appName,
                runnerLabel = if (installed) "Standalone" else "Missing",
                appPackage = pkg
            ))
        }

        return options
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String {
        // Some emulator forks deliberately keep the upstream application label. Prefer our
        // package-specific name so variants such as Citra and Citra MMJ remain distinguishable.
        knownAppLabels[packageName]?.let { return it }
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private val knownAppLabels = mapOf(
        "com.explusalpha.A2600Emu" to "2600.emu",
        "com.explusalpha.GbaEmu" to "GBA.emu",
        "com.explusalpha.GbcEmu" to "GBC.emu",
        "com.explusalpha.LynxEmu" to "Lynx.emu",
        "com.explusalpha.MdEmu" to "MD.emu",
        "com.explusalpha.NeoEmu" to "NEO.emu",
        "com.explusalpha.NesEmu" to "NES.emu",
        "com.explusalpha.NgpEmu" to "NGP.emu",
        "com.explusalpha.SaturnEmu" to "Saturn.emu",
        "com.explusalpha.Snes9xPlus" to "Snes9x EX+",
        "com.explusalpha.SwanEmu" to "Swan.emu",
        "com.PceEmu" to "PCE.emu",
        "com.fastemulator.gba" to "My Boy!",
        "com.fastemulator.gbc" to "My OldBoy!",
        "it.dbtecno.pizzaboygbapro" to "Pizza Boy GBA Pro",
        "it.dbtecno.pizzaboygba" to "Pizza Boy GBA",
        "it.dbtecno.pizzaboypro" to "Pizza Boy GBC Pro",
        "it.dbtecno.pizzaboy" to "Pizza Boy GBC",
        "com.fms.ines.free" to "iNES",
        "com.androidemu.nes" to "Nesoid",
        "org.mupen64plusae.v3.fzurita" to "M64Plus FZ",
        "org.mupen64plusae.v3.alpha" to "Mupen64Plus AE",
        "me.magnum.melonds" to "melonDS",
        "me.magnum.melonds.nightly" to "melonDS Nightly",
        "com.dsemu.drastic" to "DraStic",
        "com.fms.mg" to "MasterGear",
        "org.devmiyax.yabasanshioro2.pro" to "YabaSanshiro2 Pro",
        "org.devmiyax.yabasanshioro2" to "YabaSanshiro2",
        "com.flycast.emulator" to "Flycast",
        "io.recompiled.redream" to "Redream",
        "com.github.stenzek.duckstation" to "DuckStation",
        "com.epsxe.ePSXe" to "ePSXe",
        "com.emulator.fpse" to "FPse",
        "com.emulator.fpse64" to "FPse64",
        "org.ppsspp.ppsspp" to "PPSSPP",
        "org.ppsspp.ppssppgold" to "PPSSPP Gold",
        "xyz.aethersx2.android" to "AetherSX2",
        "org.dolphinemu.dolphinemu" to "Dolphin",
        "org.mm.jr" to "Dolphin MMJR",
        "org.dolphinemu.mmjr" to "Dolphin MMJR2",
        "org.azahar_emu.azahar" to "Azahar",
        "org.citra.citra_emu" to "Citra",
        "org.citra.emu" to "Citra MMJ",
        "io.github.lime3ds.android" to "Lime3DS",
        "com.panda3ds.pandroid" to "Panda3DS",
        "info.cemu.cemu" to "Cemu",
        "org.vita3k.emulator" to "Vita3K",
        "aenu.aps3e" to "aPS3e",
        "org.citron.citron_emu" to "Citron",
        "ru.vastness.altmer.iratajaguar" to "IrataJaguar",
        "com.fms.colem.deluxe" to "ColEm Deluxe",
        "com.fms.colem" to "ColEm",
        "com.sky.SkyEmu" to "SkyEmu",
        "com.pixelrespawn.linkboy" to "Linkboy",
        "it.dbtecno.pizzaboyscpro" to "Pizza Boy SC Pro",
        "it.dbtecno.pizzaboyscbasic" to "Pizza Boy SC Basic",
        "com.hydra.noods" to "NooDS",
        "me.magnum.melondualds" to "melonDS DualDS",
        "come.nanodata.armsx2" to "ARMSX2",
        "com.sbro.emucorex" to "EmuCoreX",
        "com.virtualapplications.play" to "Play!",
        "io.github.azaharplus.android" to "AzaharPlus",
        "org.citra.citra_emu.canary" to "Citra Canary",
        "io.github.mandarine3ds.mandarine" to "Mandarine",
        "dev.eden.eden_emulator" to "Eden",
        "dev.legacy.eden_emulator" to "Eden (Legacy)",
        "org.kenjinx.android" to "Kenji-NX",
        "skyline.emu" to "Skyline",
        "aenu.aps3e.premium" to "aPS3e Premium",
        "org.mupen64plusae.v3.fzurita.pro" to "M64Plus FZ Pro",
        "org.mupen64plusae.v3.fzurita.amazon" to "M64Plus FZ (Amazon)",
        "org.vita3k.emulator.ikhoeyZX" to "Vita3K ikhoeyZX",
        "com.seleuco.mame4droid" to "MAME4droid",
    )

    fun getDisplayName(tag: String): String {
        val upper = tag.uppercase()
        return ini.get("platforms", upper)
            ?: defaultPlatformNames[upper]
            ?: tag
    }

    fun setDisplayName(tag: String, name: String) {
        val configFile = paths.platformsIni
        val currentNames = ini.getSection("platforms").toMutableMap()
        val naturalName = defaultPlatformNames[tag.uppercase()] ?: tag
        if (name.isBlank() || name == naturalName) {
            currentNames.remove(tag)
        } else {
            currentNames[tag] = name
        }
        val cores = ini.getSection("cores")
        val sb = StringBuilder()
        sb.appendLine("[platforms]")
        for ((t, n) in currentNames) {
            sb.appendLine("%-6s = %s".format(t, n))
        }
        sb.appendLine()
        sb.appendLine("[cores]")
        for ((t, c) in cores) {
            sb.appendLine("%-6s = %s".format(t, c))
        }
        configFile.parentFile?.mkdirs()
        configFile.writeText(sb.toString())
        ini = IniParser.parse(configFile)
    }

    fun getCoreName(tag: String): String? {
        val upper = tag.uppercase()
        return userCores[tag]
            ?: ini.get("cores", upper)
            ?: defaultCores[upper]
    }

    fun getEmuLaunch(tag: String, romsDir: File): LaunchTarget.EmuLaunch? {
        val emuFile = File(romsTagDir(tag, romsDir), ".emu_launch")
        if (!emuFile.exists()) return null

        val emu = IniParser.parse(emuFile)
        val pkg = emu.get("emulator", "package") ?: return null
        val activity = emu.get("emulator", "activity") ?: return null
        val action = emu.get("emulator", "action") ?: "android.intent.action.VIEW"

        return LaunchTarget.EmuLaunch(pkg, activity, action)
    }

    fun resolvePlatform(tag: String, romsDir: File, gameCount: Int): Platform {
        val hasEmu = File(romsTagDir(tag, romsDir), ".emu_launch").exists()
        return Platform(
            tag = tag,
            displayName = getDisplayName(tag),
            coreName = getCoreName(tag),
            hasEmuLaunch = hasEmu,
            gameCount = gameCount
        )
    }

    private fun writeDefaultIni(file: File) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("[platforms]")
        for ((tag, name) in defaultPlatformNames) {
            sb.appendLine("%-6s = %s".format(tag, name))
        }
        sb.appendLine()
        sb.appendLine("[cores]")
        sb.appendLine("; Optional - overrides bundled TAG->core lookup")
        sb.appendLine("; GBA = mgba_libretro")
        file.writeText(sb.toString())
    }

    companion object {
        fun parseAppConfigForTest(obj: JSONObject): AppConfig = parseAppConfig(obj)

        private fun parseAppConfig(obj: JSONObject): AppConfig {
            val pkg = obj.optString("package", "").ifEmpty {
                throw IllegalArgumentException("AppConfig: missing required `package`")
            }
            val activity = obj.optString("activity", "").ifEmpty { null }
            val action = obj.optString("action", "").ifEmpty { null } ?: Intent.ACTION_VIEW
            val data = obj.optJSONObject("data")?.let(::parseDataBinding) ?: DataBinding.None
            val extras = obj.optJSONArray("extras")?.let { arr ->
                (0 until arr.length()).map { parseExtraSpec(arr.getJSONObject(it)) }
            } ?: emptyList()
            val mimeType = if (obj.has("mimeType")) {
                if (obj.isNull("mimeType")) null else obj.getString("mimeType")
            } else "*/*"
            val intentFlags = obj.optInt("intentFlags", Intent.FLAG_ACTIVITY_NEW_TASK)
            val launchMethod = parseLaunchMethod(obj.optString("launchMethod", "intent"))
            return AppConfig(pkg, activity, action, data, extras, mimeType, intentFlags, launchMethod)
        }

        private fun parseDataBinding(obj: JSONObject): DataBinding {
            val kind = obj.optString("kind", "")
            return when (kind) {
                "none" -> DataBinding.None
                "file_provider" -> DataBinding.FileProvider(grantPermission = obj.optBoolean("grantPermission", true))
                "absolute_path" -> DataBinding.AbsolutePath
                "external_storage_saf" -> DataBinding.ExternalStorageSaf
                "custom_scheme" -> DataBinding.CustomScheme(
                    scheme = obj.optString("scheme").ifEmpty {
                        throw IllegalArgumentException("custom_scheme: missing `scheme`")
                    },
                    authority = obj.optString("authority").ifEmpty {
                        throw IllegalArgumentException("custom_scheme: missing `authority`")
                    },
                )
                else -> throw IllegalArgumentException("Unknown data.kind: `$kind`")
            }
        }

        private fun parseExtraSpec(obj: JSONObject): ExtraSpec {
            val key = obj.optString("key").ifEmpty {
                throw IllegalArgumentException("ExtraSpec: missing `key`")
            }
            val kind = when (obj.optString("kind")) {
                "path" -> ExtraValueKind.FILE_PATH
                "uri_string" -> ExtraValueKind.FILE_URI_STRING
                "uri_parcelable" -> ExtraValueKind.FILE_URI_PARCELABLE
                "string_array" -> ExtraValueKind.STRING_ARRAY
                else -> throw IllegalArgumentException("ExtraSpec `${key}`: unknown kind `${obj.optString("kind")}`")
            }
            val values = if (kind == ExtraValueKind.STRING_ARRAY) {
                val arr = obj.optJSONArray("values")
                    ?: throw IllegalArgumentException("ExtraSpec `${key}`: kind `string_array` requires `values`")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            return ExtraSpec(key, kind, values)
        }

        private fun parseLaunchMethod(s: String): LaunchMethod = when (s) {
            "intent" -> LaunchMethod.INTENT
            "shell" -> LaunchMethod.SHELL
            else -> throw IllegalArgumentException("Unknown launchMethod: `$s`")
        }
    }
}
