package dev.cannoli.scorza.scanner

import android.content.pm.PackageManager
import android.content.res.AssetManager
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.IniData
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

data class GameCoreOverride(val coreId: String = "", val runner: String? = null, val appPackage: String? = null, val raPackage: String? = null)

data class AppConfig(
    val packageName: String,
    val activity: String? = null,
    val action: String? = null,
    val data: dev.cannoli.scorza.launcher.ApkLauncher.DataKind = dev.cannoli.scorza.launcher.ApkLauncher.DataKind.NONE,
    val extraKey: String? = null,
    val extraKind: dev.cannoli.scorza.launcher.ApkLauncher.DataKind = dev.cannoli.scorza.launcher.ApkLauncher.DataKind.NONE
)

private fun parseDataKind(s: String?): dev.cannoli.scorza.launcher.ApkLauncher.DataKind = when (s?.lowercase()) {
    "saf" -> dev.cannoli.scorza.launcher.ApkLauncher.DataKind.SAF
    "provider" -> dev.cannoli.scorza.launcher.ApkLauncher.DataKind.PROVIDER
    "path" -> dev.cannoli.scorza.launcher.ApkLauncher.DataKind.PATH
    else -> dev.cannoli.scorza.launcher.ApkLauncher.DataKind.NONE
}

class PlatformResolver(
    private val cannoliRoot: File,
    private val assets: AssetManager,
    private val coreInfo: CoreInfoRepository? = null,
    private val nativeLibDir: String? = null
) {

    private var defaultCores = mapOf<String, String>()
    private var defaultPlatformNames = mapOf<String, String>()
    private var defaultApps = mapOf<String, List<AppConfig>>()
    private var arcadePlatforms = setOf<String>()

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
                    when (item) {
                        is String -> list.add(AppConfig(packageName = item))
                        is JSONObject -> list.add(AppConfig(
                            packageName = item.getString("package"),
                            activity = item.optString("activity").ifEmpty { null },
                            action = item.optString("action").ifEmpty { null },
                            data = parseDataKind(item.optString("data").ifEmpty { null }),
                            extraKey = item.optString("extraKey").ifEmpty { null },
                            extraKind = parseDataKind(item.optString("extraKind").ifEmpty { null })
                        ))
                    }
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
    private var userPackages: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var gameOverrides: MutableMap<String, GameCoreOverride> = java.util.concurrent.ConcurrentHashMap()
    private val coresFile get() = File(cannoliRoot, "Config/cores.json")

    private fun romsTagDir(tag: String, romsDir: File = File(cannoliRoot, "Roms")): File {
        val direct = File(romsDir, tag)
        if (direct.exists()) return direct
        return romsDir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(tag, ignoreCase = true) } ?: direct
    }

    fun load() {
        loadPlatformsAsset()
        val configFile = File(cannoliRoot, "Config/platforms.ini")
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
        userPackages.clear()
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
            val packages = json.optJSONObject("packages")
            if (packages != null) for (key in packages.keys()) userPackages[key] = packages.getString(key)
            val overrides = json.optJSONObject("gameOverrides")
            if (overrides != null) {
                for (path in overrides.keys()) {
                    val obj = overrides.getJSONObject(path)
                    gameOverrides[path] = GameCoreOverride(
                        coreId = obj.optString("core", ""),
                        runner = obj.optString("runner", "").ifEmpty { null },
                        appPackage = obj.optString("app", "").ifEmpty { null },
                        raPackage = obj.optString("raPackage", "").ifEmpty { null }
                    )
                }
            }
        } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
        migrateStaleInternalRunners()
    }

    private fun migrateStaleInternalRunners() {
        var dirty = false
        for ((tag, runner) in userRunners.toMap()) {
            if (runner != "Internal") continue
            if (defaultCores.containsKey(tag)) continue
            userRunners.remove(tag)
            userCores.remove(tag)
            dirty = true
        }
        if (dirty) saveCoreMappings()
    }

    fun reloadCoreMappings() {
        loadCoreMappings()
    }

    fun purgeStaleRaMappings(installedRaCores: Map<String, Set<String>>): Boolean {
        var changed = false
        for (tag in userPackages.keys.toList()) {
            val pkg = userPackages[tag] ?: continue
            val coreId = getCoreMapping(tag)
            val cores = installedRaCores[pkg]
            if (cores == null || coreId !in cores) {
                userRunners.remove(tag)
                userPackages.remove(tag)
                changed = true
            }
        }
        val staleOverrides = gameOverrides.entries.filter { (_, ov) ->
            val pkg = ov.raPackage ?: return@filter false
            val cores = installedRaCores[pkg]
            val coreId = ov.coreId ?: return@filter false
            cores == null || coreId !in cores
        }.map { it.key }
        for (path in staleOverrides) {
            gameOverrides.remove(path)
            changed = true
        }
        if (changed) saveCoreMappings()
        return changed
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
        if (userPackages.isNotEmpty()) {
            val packages = JSONObject()
            for ((tag, pkg) in userPackages) packages.put(tag, pkg)
            json.put("packages", packages)
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
                    if (ov.raPackage != null) obj.put("raPackage", ov.raPackage)
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

    fun setCoreMapping(tag: String, core: String, runner: String? = null, raPackage: String? = null) {
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
        if (raPackage != null) {
            userPackages[tag] = raPackage
        } else {
            userPackages.remove(tag)
        }
        userApps.remove(tag)
    }

    fun getPackage(tag: String): String? = userPackages[tag]

    fun getRunnerPreference(tag: String): String? = userRunners[tag]

    fun getGameOverride(gamePath: String): GameCoreOverride? = gameOverrides[gamePath]

    fun setGameOverride(gamePath: String, coreId: String?, runner: String?, raPackage: String? = null) {
        if (coreId == null) {
            gameOverrides.remove(gamePath)
        } else {
            gameOverrides[gamePath] = GameCoreOverride(coreId, runner, raPackage = raPackage)
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
        userPackages.remove(tag)
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

    fun getRunnerLabel(tag: String, coreId: String, installedRaCores: Map<String, Set<String>> = emptyMap()): String {
        if (File(romsTagDir(tag), ".emu_launch").exists()) return "External"
        val override = userRunners[tag]
        if (override == "App") return "Standalone"
        if (override != null) return override
        val pkg = userPackages[tag]
        if (pkg != null) return InstalledCoreService.getPackageLabel(pkg)
        if (nativeLibDir != null && File(nativeLibDir, "${coreId}_android.so").exists()) return "Internal"
        if (installedRaCores.any { it.value.contains(coreId) }) return "RetroArch"
        return "RetroArch"
    }

    fun getDetailedMappings(
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unresponsivePackages: Set<String> = emptySet()
    ): List<dev.cannoli.scorza.ui.screens.CoreMappingEntry> {
        val tags = (defaultCores.keys + defaultApps.keys + userCores.keys + userApps.keys)
        return tags.map { tag ->
            val app = getAppPackage(tag)
            val coreId = getCoreMapping(tag)
            val installedApp: String? = when {
                pm == null -> app
                else -> getAppOptions(tag).firstOrNull { pm.isPackageInstalled(it.packageName) }?.packageName
            }
            if (app != null && coreId.isBlank()) {
                if (installedApp != null) {
                    val appName = pm?.let { resolveAppLabel(it, installedApp) } ?: (knownAppLabels[installedApp] ?: installedApp)
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone"
                    )
                } else {
                    val firstApp = getAppOptions(tag).firstOrNull()?.packageName
                    val label = firstApp?.let { knownAppLabels[it] ?: it } ?: "Missing"
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = label, runnerLabel = "Missing"
                    )
                }
            } else if (coreId.isBlank()) {
                dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                    tag = tag, platformName = getDisplayName(tag),
                    coreDisplayName = "None", runnerLabel = ""
                )
            } else {
                val runner = getRunnerLabel(tag, coreId, installedRaCores)
                val status = coreStatus(tag, coreId, runner, installedRaCores, embeddedCoresDir, unresponsivePackages)
                if (status == "Missing" && installedApp != null) {
                    val appName = pm?.let { resolveAppLabel(it, installedApp) } ?: installedApp
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = appName, runnerLabel = "Standalone"
                    )
                } else {
                    dev.cannoli.scorza.ui.screens.CoreMappingEntry(
                        tag = tag, platformName = getDisplayName(tag),
                        coreDisplayName = getCoreDisplayName(coreId),
                        runnerLabel = if (status == "Present") runner else status
                    )
                }
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
        val pkg = userPackages[tag]
        if (pkg != null) {
            if (installedRaCores[pkg]?.contains(coreId) == true) return "Present"
            if (pkg in unresponsivePackages) return "Unknown"
            return "Missing"
        }
        if (installedRaCores.any { it.value.contains(coreId) }) return "Present"
        if (unresponsivePackages.isNotEmpty()) return "Unknown"
        return "Missing"
    }

    fun getCorePickerOptions(
        tag: String,
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unresponsivePackages: Set<String> = emptySet()
    ): List<dev.cannoli.scorza.ui.screens.CorePickerOption> {
        val options = mutableListOf<dev.cannoli.scorza.ui.screens.CorePickerOption>()

        val candidateCoreIds = mutableSetOf<String>()
        val upper = tag.uppercase()
        defaultCores[upper]?.let { candidateCoreIds.add(it) }
        coreInfo?.getCoresForTag(tag)?.forEach { candidateCoreIds.add(it.id) }

        for (coreId in candidateCoreIds) {
            val displayName = getCoreDisplayName(coreId)

            val checkDir = embeddedCoresDir ?: nativeLibDir
            if (checkDir != null && File(checkDir, "${coreId}_android.so").exists()) {
                options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                    coreId = coreId, displayName = displayName, runnerLabel = "Internal"
                ))
            }

            for ((pkg, cores) in installedRaCores) {
                if (coreId in cores) {
                    options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                        coreId = coreId, displayName = displayName,
                        runnerLabel = InstalledCoreService.getPackageLabel(pkg),
                        raPackage = pkg
                    ))
                }
            }

            for (pkg in unresponsivePackages) {
                if (installedRaCores.containsKey(pkg)) continue
                val label = InstalledCoreService.getPackageLabel(pkg)
                options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                    coreId = coreId, displayName = displayName,
                    runnerLabel = "$label (Unknown)",
                    raPackage = pkg
                ))
            }
        }

        val appPackages = getAppOptions(tag)
        for (config in appPackages) {
            val pkg = config.packageName
            val appName = pm?.let { resolveAppLabel(it, pkg) } ?: (knownAppLabels[pkg] ?: pkg)
            val installed = pm?.isPackageInstalled(pkg) ?: true
            options.add(dev.cannoli.scorza.ui.screens.CorePickerOption(
                coreId = "", displayName = appName,
                runnerLabel = if (installed) "Standalone" else "Missing",
                appPackage = pkg
            ))
        }

        return options
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String {
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            knownAppLabels[packageName] ?: packageName
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
        val configFile = File(cannoliRoot, "Config/platforms.ini")
        val currentNames = ini.getSection("platforms").toMutableMap()
        val defaultName = defaultPlatformNames[tag.uppercase()]
        if (name == defaultName || name == tag) {
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
}
