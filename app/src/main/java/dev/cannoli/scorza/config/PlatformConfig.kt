package dev.cannoli.scorza.config

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.ui.screens.CoreAvailability
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import dev.cannoli.core.IniData
import dev.cannoli.core.IniParser
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

private const val UNGROUPED = "Other"

class PlatformConfig(
    private val cannoliRootProvider: () -> File,
    private val assets: AssetManager,
    private val coreInfo: CoreInfoRepository? = null,
    private val nativeLibDir: String? = null,
    private val emptyOverrideLabel: String = "(empty override)",
    private val needsSetupLabel: String = "Needs setup",
    private val internalLabel: String = "Internal",
    private val standaloneLabel: String = "Standalone",
) {

    constructor(
        cannoliRoot: File,
        assets: AssetManager,
        coreInfo: CoreInfoRepository? = null,
        nativeLibDir: String? = null,
    ) : this({ cannoliRoot }, assets, coreInfo, nativeLibDir)

    private var defaultCores = mapOf<String, String>()
    private var defaultPlatformNames = mapOf<String, String>()

    private var platformGroups = mapOf<String, String>()

    private var platformGroupOrder = listOf<String>()
    private var defaultApps = mapOf<String, List<AppConfig>>()
    private var arcadePlatforms = setOf<String>()
    private var stickDpadPlatforms = setOf<String>()

    init {
        loadPlatformsAsset()
    }

    private fun loadPlatformsAsset() {
        val json = JSONObject(assets.open("platforms.json").use { it.bufferedReader().readText() })
        val cores = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val groups = mutableMapOf<String, String>()
        val apps = mutableMapOf<String, List<AppConfig>>()
        val arcade = mutableSetOf<String>()
        val stickDpad = mutableSetOf<String>()
        for (tag in json.keys()) {
            val entry = json.getJSONObject(tag)
            entry.optString("name", "").takeIf { it.isNotEmpty() }?.let { names[tag] = it }
            entry.optString("core", "").takeIf { it.isNotEmpty() }?.let { cores[tag] = it }
            entry.optString("group", "").takeIf { it.isNotEmpty() }?.let { groups[tag] = it }
            if (entry.optBoolean("arcade")) arcade.add(tag)
            if (entry.optBoolean("force_stick_dpad")) stickDpad.add(tag)
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
        platformGroups = groups
        platformGroupOrder = groups.values.distinct()
            .sortedWith(compareBy<String> { if (it == UNGROUPED) 1 else 0 }.thenBy(NaturalSort) { it })
        defaultApps = apps
        arcadePlatforms = arcade
        stickDpadPlatforms = stickDpad
    }

    private var ini: IniData = IniData(emptyMap())
    private var userChoices: MutableMap<String, EmulatorChoice> = java.util.concurrent.ConcurrentHashMap()
    private var pendingV1Overrides: MutableMap<String, EmulatorChoice> = java.util.concurrent.ConcurrentHashMap()
    private val paths: CannoliPaths get() = CannoliPaths(cannoliRootProvider())
    private val coresFile get() = paths.coresJson

    fun load() {
        loadPlatformsAsset()
        val configFile = paths.platformsIni
        if (!configFile.exists()) {
            writeDefaultIni(configFile)
        }
        ini = IniParser.parse(configFile)
        loadCoreMappings()
    }

    var loadFailed: Boolean = false
        private set

    private fun loadCoreMappings() {
        loadFailed = false
        userChoices.clear()
        pendingV1Overrides.clear()
        if (!coresFile.exists()) return
        try {
            val json = JSONObject(coresFile.readText())
            val version = json.optInt("v", 1)
            if (version >= 2) loadV2(json) else migrateV1(json)
        } catch (e: java.io.IOException) {
            loadFailed = true
            dev.cannoli.scorza.util.ErrorLog.write("cores.json unreadable: ${e.message}")
        } catch (e: org.json.JSONException) {
            loadFailed = true
            dev.cannoli.scorza.util.ErrorLog.write("cores.json unparseable: ${e.message}")
        }
    }

    private fun loadV2(json: JSONObject) {
        val obj = json.optJSONObject("platforms") ?: return
        for (tag in obj.keys()) readChoice(obj.getJSONObject(tag))?.let { userChoices[tag] = it }
    }

    private fun readChoice(obj: JSONObject): EmulatorChoice? {
        val rawSource = obj.optString("source", "")
        val source = readSource(rawSource) ?: return null
        val coreId = obj.optString("core", "")
        val app = obj.optString("app", "").ifEmpty { null }
            ?.takeUnless { rawSource == EmulatorSource.RETIRED_EXTERNAL_RA_SOURCE }
        if (source != EmulatorSource.Standalone && coreId.isEmpty()) {
            dev.cannoli.scorza.util.ErrorLog.write("dropping $source choice with no core")
            return null
        }
        if (source == EmulatorSource.Standalone && app == null) return null
        return EmulatorChoice(source = source, coreId = coreId, appPackage = app)
    }

    private fun readSource(name: String): EmulatorSource? =
        if (name == EmulatorSource.RETIRED_INTERNAL_SOURCE ||
            name == EmulatorSource.RETIRED_EXTERNAL_RA_SOURCE
        ) EmulatorSource.Embedded
        else runCatching { EmulatorSource.valueOf(name) }.getOrNull()

    private fun migrateV1(json: JSONObject) {
        val cores = json.optJSONObject("cores")
        val runners = json.optJSONObject("runners")
        val apps = json.optJSONObject("apps")
        val tags = buildSet {
            cores?.keys()?.forEach { add(it) }
            runners?.keys()?.forEach { add(it) }
            apps?.keys()?.forEach { add(it) }
        }
        for (tag in tags) {
            val coreId = cores?.optString(tag, "").orEmpty()
            val app = apps?.optString(tag, "")?.ifEmpty { null }
            legacyChoice(
                runners?.optString(tag, "")?.ifEmpty { null }, app, coreId,
                fallbackCoreId = defaultCores[tag.uppercase()],
            )?.let { userChoices[tag] = it }
        }
        val overrides = json.optJSONObject("gameOverrides") ?: return
        for (path in overrides.keys()) {
            val obj = overrides.getJSONObject(path)
            legacyChoice(
                obj.optString("runner", "").ifEmpty { null },
                obj.optString("app", "").ifEmpty { null },
                obj.optString("core", ""),
            )?.let { pendingV1Overrides[path] = it }
        }
    }

    fun migrateV1GameOverrides(resolveRomId: (String) -> Long?, put: (Long, EmulatorChoice) -> Unit) {
        if (pendingV1Overrides.isEmpty()) return
        var moved = 0
        for ((path, choice) in pendingV1Overrides) {
            val romId = resolveRomId(path)
            if (romId == null) {
                dev.cannoli.scorza.util.ErrorLog.write("game override dropped, no rom row: $path")
                continue
            }
            put(romId, choice)
            moved++
        }
        dev.cannoli.scorza.util.ErrorLog.write("migrated $moved v1 game overrides to the database")
        pendingV1Overrides.clear()
        saveCoreMappings()
    }

    internal fun legacyChoice(
        runner: String?,
        app: String?,
        coreId: String,
        fallbackCoreId: String? = null,
    ): EmulatorChoice? {
        val source = EmulatorSource.fromRunnerLabel(runner) ?: when {
            app != null -> EmulatorSource.Standalone
            coreId.isEmpty() && fallbackCoreId.isNullOrEmpty() -> return null
            else -> EmulatorSource.Embedded
        }
        if (source == EmulatorSource.Standalone) return EmulatorChoice(source, coreId, app)
        val resolved = coreId.ifEmpty { fallbackCoreId.orEmpty() }
        if (resolved.isEmpty()) return null
        return EmulatorChoice(source, resolved, appPackage = null)
    }

    fun reloadCoreMappings() {
        loadCoreMappings()
    }

    fun saveCoreMappings() {
        if (loadFailed) {
            dev.cannoli.scorza.util.ErrorLog.write("cores.json save skipped: last load failed")
            return
        }
        val json = JSONObject().put("v", CORES_JSON_VERSION)
        if (userChoices.isNotEmpty()) {
            val platforms = JSONObject()
            for ((tag, choice) in userChoices) {
                platforms.put(tag, JSONObject()
                    .put("source", choice.source.name)
                    .apply {
                        if (choice.coreId.isNotEmpty()) put("core", choice.coreId)
                        if (choice.appPackage != null) put("app", choice.appPackage)
                    })
            }
            json.put("platforms", platforms)
        }
        coresFile.parentFile?.mkdirs()
        val tmp = File(coresFile.parentFile, "${coresFile.name}.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString(2).toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(coresFile)) {
                tmp.delete()
                dev.cannoli.scorza.util.ErrorLog.write("cores.json atomic rename failed")
            }
        } catch (e: java.io.IOException) {
            tmp.delete()
            dev.cannoli.scorza.util.ErrorLog.write("cores.json write failed: ${e.message}")
        }
    }

    fun seedUnsetPlatforms(pm: PackageManager): Int {
        if (loadFailed) return 0
        var written = 0
        for (tag in getAllTags()) {
            if (userChoices.containsKey(tag)) continue
            val choice = seedFor(tag, pm) ?: continue
            userChoices[tag] = choice
            written++
        }
        if (written > 0) saveCoreMappings()
        return written
    }

    private fun seedFor(tag: String, pm: PackageManager): EmulatorChoice? {
        val upper = tag.uppercase()
        val dir = nativeLibDir
        if (dir != null) {
            val candidates = buildList {
                defaultCores[upper]?.let { add(it) }
                coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
            }
            candidates.firstOrNull { File(dir, "${it}_android.so").exists() }
                ?.let { return EmulatorChoice(EmulatorSource.Embedded, it) }
        }
        val installed = getAppOptions(tag).filter { pm.isPackageInstalled(it.packageName) }
        if (installed.size == 1) {
            return EmulatorChoice(EmulatorSource.Standalone, appPackage = installed.single().packageName)
        }
        return null
    }

    fun getPlatformChoice(tag: String): EmulatorChoice? = userChoices[tag]

    fun setPlatformChoice(tag: String, choice: EmulatorChoice) {
        userChoices[tag] = choice
        saveCoreMappings()
    }

    fun getCoreMapping(tag: String): String {
        val picked = userChoices[tag]?.takeIf { it.source != EmulatorSource.Standalone }?.coreId
        if (!picked.isNullOrEmpty()) return picked
        val upper = tag.uppercase()
        return ini.get("cores", upper) ?: defaultCores[upper] ?: ""
    }

    fun describeChoice(choice: EmulatorChoice, pm: PackageManager? = null): String =
        when (choice.source) {
            EmulatorSource.Standalone -> {
                val pkg = choice.appPackage ?: return emptyOverrideLabel
                pm?.let { resolveAppLabel(it, pkg) } ?: (knownAppLabels[pkg] ?: pkg)
            }
            EmulatorSource.Embedded ->
                "${internalLabel}: ${getCoreDisplayName(choice.coreId)}"
        }


    fun resetPlatformToDefault(tag: String, pm: PackageManager): EmulatorChoice? {
        val seeded = seedFor(tag, pm)
        if (seeded == null) userChoices.remove(tag) else userChoices[tag] = seeded
        saveCoreMappings()
        return seeded
    }

    fun hasUserMapping(tag: String): Boolean = userChoices.containsKey(tag)

    fun isKnownTag(tag: String): Boolean {
        val upper = tag.uppercase()
        return upper in defaultPlatformNames || upper in ini.getSection("platforms")
    }

    fun isArcade(tag: String): Boolean = tag.uppercase() in arcadePlatforms

    fun forcesStickDpad(tag: String): Boolean = tag.uppercase() in stickDpadPlatforms

    fun getAllTags(): Set<String> = defaultPlatformNames.keys + ini.getSection("platforms").keys

    fun groupRank(group: String?): Int =
        if (group == null) Int.MAX_VALUE else platformGroupOrder.indexOf(group).takeIf { it >= 0 } ?: Int.MAX_VALUE

    fun getGroup(tag: String): String? = platformGroups[tag.uppercase(java.util.Locale.ROOT)]

    fun getGroups(tags: Collection<String>): Map<String, String> =
        tags.mapNotNull { tag -> getGroup(tag)?.let { tag to it } }.toMap()

    fun getAppPackage(tag: String): String? =
        userChoices[tag]?.appPackage ?: defaultApps[tag.uppercase()]?.firstOrNull()?.packageName

    fun getUserAppMapping(tag: String): String? =
        userChoices[tag]?.takeIf { it.source == EmulatorSource.Standalone }?.appPackage

    fun getAppOptions(tag: String): List<AppConfig> = defaultApps[tag.uppercase()] ?: emptyList()

    fun getAppConfig(tag: String, packageName: String): AppConfig {
        val configs = getAppOptions(tag)
        return configs.firstOrNull { it.packageName == packageName } ?: AppConfig(packageName)
    }

    fun getFirstInstalledApp(tag: String, pm: PackageManager): AppConfig? {
        return getAppOptions(tag).firstOrNull { pm.isPackageInstalled(it.packageName) }
    }


    fun getCoreDisplayName(coreId: String): String {
        return coreInfo?.getDisplayName(coreId) ?: coreId
    }

    fun getMissingFirmware(coreId: String, biosDir: File): List<FirmwareEntry> {
        return coreInfo?.getMissingFirmware(coreId, biosDir) ?: emptyList()
    }

    fun getFirmwareStatus(tag: String, coreId: String, biosDir: File): List<FirmwareRequirement> {
        val all = coreInfo?.getFirmwareFor(coreId) ?: emptyList()
        val rule = biosRules[tag.uppercase(java.util.Locale.ROOT) to coreId]
        fun present(entry: FirmwareEntry): Boolean {
            val name = File(entry.path).name
            return File(biosDir, entry.path).exists() || File(biosDir, name).exists()
        }
        val grouped = rule?.anyOf.orEmpty()
        val claimed = grouped.flatten().toSet()
        val byName = all.associateBy { File(it.path).name }
        val groups = grouped.mapNotNull { names ->
            val options = names.mapNotNull { byName[File(it).name] }.map { it to present(it) }
            options.takeIf { it.isNotEmpty() }?.let { FirmwareRequirement.AnyOf(it) }
        }
        val singles = all
            .filterNot { File(it.path).name in claimed.map { c -> File(c).name } }
            .map { entry ->
                val name = File(entry.path).name
                val corrected =
                    if (entry.optional && name in rule?.required.orEmpty()) entry.copy(optional = false) else entry
                FirmwareRequirement.Single(corrected, present(entry))
            }
        return groups + singles
    }

    fun coreExtensions(coreId: String): List<String> = coreInfo?.getExtensionsFor(coreId).orEmpty()

    internal data class BiosRule(val required: Set<String>, val anyOf: List<List<String>>)

    private val biosRules: Map<Pair<String, String>, BiosRule> by lazy {
        val parsed = mutableMapOf<Pair<String, String>, BiosRule>()
        try {
            val text = assets.open("bios_required.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val tag = obj.optString("tag").uppercase(java.util.Locale.ROOT)
                val core = obj.optString("core")
                if (tag.isEmpty() || core.isEmpty()) continue
                val required = obj.optJSONArray("required")
                    ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } }
                    .orEmpty().map { File(it).name }.toSet()
                val anyOf = obj.optJSONArray("anyOf")?.let { groups ->
                    (0 until groups.length()).mapNotNull { g ->
                        groups.optJSONArray(g)?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) }
                        }?.takeIf { it.isNotEmpty() }
                    }
                }.orEmpty()
                parsed[tag to core] = BiosRule(required, anyOf)
            }
        } catch (e: Exception) {
            dev.cannoli.scorza.util.ErrorLog.write("bios_required.json unreadable: ${e.message}")
        }
        parsed
    }

    fun getRunnerLabel(tag: String, coreId: String): String {
        val choice = userChoices[tag]
        return when (choice?.source ?: EmulatorSource.Embedded) {
            EmulatorSource.Embedded -> internalLabel
            EmulatorSource.Standalone -> standaloneLabel
        }
    }

    fun getDetailedMappings(
        pm: PackageManager? = null,
        embeddedCoresDir: String? = null,
    ): List<dev.cannoli.scorza.ui.screens.EmulatorMappingEntry> {
        val tags = (defaultCores.keys + defaultApps.keys + userChoices.keys)
        return tags.map { tag ->
            detailedMappingFor(tag, pm, embeddedCoresDir)
        }.sortedNatural { it.platformName }
    }

    fun detailedMappingFor(
        tag: String,
        pm: PackageManager? = null,
        embeddedCoresDir: String? = null,
    ): dev.cannoli.scorza.ui.screens.EmulatorMappingEntry {
        val choice = userChoices[tag]
        val app = getAppPackage(tag)
        val coreId = getCoreMapping(tag)
        val installedApp: String? = when {
            pm == null -> app
            else -> getAppOptions(tag).firstOrNull { pm.isPackageInstalled(it.packageName) }?.packageName
        }
        val effectiveSource = choice?.source
            ?: if (app != null && coreId.isBlank()) EmulatorSource.Standalone else null

        return if (effectiveSource == EmulatorSource.Standalone) {
            val resolved = choice?.appPackage ?: installedApp
            if (resolved != null) {
                val appName = pm?.let { resolveAppLabel(it, resolved) } ?: (knownAppLabels[resolved] ?: resolved)
                val installed = pm == null || pm.isPackageInstalled(resolved)
                dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                    tag = tag, platformName = getDisplayName(tag), group = getGroup(tag),
                    coreDisplayName = appName, runnerLabel = "Standalone",
                    status = if (installed) EmulatorMappingStatus.READY else EmulatorMappingStatus.NOT_INSTALLED
                )
            } else {
                dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                    tag = tag, platformName = getDisplayName(tag), group = getGroup(tag),
                    coreDisplayName = needsSetupLabel, runnerLabel = "",
                    status = EmulatorMappingStatus.NEEDS_SETUP
                )
            }
        } else if (coreId.isBlank()) {
            dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                tag = tag, platformName = getDisplayName(tag), group = getGroup(tag),
                coreDisplayName = needsSetupLabel, runnerLabel = "",
                status = EmulatorMappingStatus.NEEDS_SETUP
            )
        } else {
            val resolvedRunner = getRunnerLabel(tag, coreId)
            val status = coreStatus(tag, coreId, resolvedRunner, embeddedCoresDir)
            val mappingStatus =
                if (status == "Missing") EmulatorMappingStatus.NOT_INSTALLED
                else EmulatorMappingStatus.READY
            dev.cannoli.scorza.ui.screens.EmulatorMappingEntry(
                tag = tag, platformName = getDisplayName(tag), group = getGroup(tag),
                coreDisplayName = getCoreDisplayName(coreId),
                runnerLabel = resolvedRunner,
                status = mappingStatus
            )
        }
    }

    private fun coreStatus(
        tag: String, coreId: String, runner: String,
        embeddedCoresDir: String?,
    ): String {
        if (runner == "External") return "Present"
        val dir = embeddedCoresDir ?: nativeLibDir ?: return "Missing"
        return if (File(dir, "${coreId}_android.so").exists()) "Present" else "Missing"
    }

    fun availableSources(tag: String, embeddedCoresDir: String? = null): List<EmulatorSource> {
        val upper = tag.uppercase()
        val candidateCores = buildSet {
            defaultCores[upper]?.let { add(it) }
            coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
        }
        val hasCoreCandidates = candidateCores.isNotEmpty()
        val hasStandaloneCandidates = getAppOptions(tag).isNotEmpty()
        return buildList {
            if (hasCoreCandidates) add(EmulatorSource.Embedded)
            if (hasStandaloneCandidates) add(EmulatorSource.Standalone)
        }
    }

    fun emulatorOptionsForSource(
        tag: String,
        source: EmulatorSource,
        includeAll: Boolean,
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
            EmulatorSource.Embedded -> candidateCoreIds.mapNotNull { coreId ->
                val present = embeddedDir != null &&
                    File(embeddedDir, "${coreId}_android.so").exists() &&
                    dev.cannoli.scorza.launcher.SystemFiles.remoteSetsPresent(
                        assets, coreId, upper, paths.biosFor(upper)
                    )
                when {
                    present -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId),
                        source = EmulatorSource.Embedded,
                        runnerLabel = internalLabel,
                    )
                    includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = coreId, displayName = getCoreDisplayName(coreId),
                        source = EmulatorSource.Embedded,
                        runnerLabel = internalLabel,
                        availability = CoreAvailability.UNAVAILABLE,
                    )
                    else -> null
                }
            }
            EmulatorSource.Standalone -> getAppOptions(tag).mapNotNull { cfg ->
                val installed = pm?.isPackageInstalled(cfg.packageName) ?: true
                val appName = pm?.let { resolveAppLabel(it, cfg.packageName) } ?: (knownAppLabels[cfg.packageName] ?: cfg.packageName)
                when {
                    installed -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = "", displayName = appName,
                        source = EmulatorSource.Standalone, runnerLabel = standaloneLabel,
                        appPackage = cfg.packageName,
                    )
                    includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                        coreId = "", displayName = appName,
                        source = EmulatorSource.Standalone, runnerLabel = standaloneLabel,
                        appPackage = cfg.packageName, availability = CoreAvailability.UNAVAILABLE,
                    )
                    else -> null
                }
            }
        }
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String {
        knownAppLabels[packageName]?.let { return it }
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private val knownAppLabels = mapOf(
        "org.mupen64plusae.v3.fzurita" to "M64Plus FZ",
        "org.mupen64plusae.v3.fzurita.pro" to "M64Plus FZ Pro",
        "me.magnum.melonds" to "melonDS",
        "com.dsemu.drastic" to "DraStic",
        "org.devmiyax.yabasanshioro2" to "YabaSanshiro2",
        "com.flycast.emulator" to "Flycast",
        "io.recompiled.redream" to "Redream",
        "com.github.stenzek.duckstation" to "DuckStation",
        "org.ppsspp.ppsspp" to "PPSSPP",
        "org.ppsspp.ppssppgold" to "PPSSPP Gold",
        "xyz.aethersx2.android" to "NetherSX2",
        "org.dolphinemu.dolphinemu" to "Dolphin",
        "org.dolphinemu.mmjr" to "Dolphin MMJR2",
        "dev.cannoli.delfino" to "Delfino",
        "org.azahar_emu.azahar" to "Azahar",
        "info.cemu.cemu" to "Cemu",
        "org.vita3k.emulator" to "Vita3K",
        "aenu.aps3e" to "aPS3e",
        "me.magnum.melondualds" to "WatermelonDS",
        "com.armsx2" to "ARMSX2",
        "dev.eden.eden_emulator" to "Eden",
        "dev.eden.eden_emulator.nightly" to "Eden Nightly",
        "dev.legacy.eden_emulator" to "Eden (Legacy)",
        "app.gamenative" to "GameNative",
        "gamehub.lite" to "GameHub Lite",
        "io.wip.pico8" to "PICO-8 for Android",
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

    fun getCoreName(tag: String): String? = getCoreMapping(tag).ifEmpty { null }

    fun resolvePlatform(tag: String, romsDir: File, gameCount: Int): Platform =
        Platform(
            tag = tag,
            displayName = getDisplayName(tag),
            coreName = getCoreName(tag),
            gameCount = gameCount
        )

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
        const val CORES_JSON_VERSION = 3

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
                "string" -> ExtraValueKind.STRING
                "int" -> ExtraValueKind.INT
                "bool" -> ExtraValueKind.BOOL
                else -> throw IllegalArgumentException("ExtraSpec `${key}`: unknown kind `${obj.optString("kind")}`")
            }
            val values = if (kind == ExtraValueKind.STRING_ARRAY) {
                val arr = obj.optJSONArray("values")
                    ?: throw IllegalArgumentException("ExtraSpec `${key}`: kind `string_array` requires `values`")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            val value = if (kind == ExtraValueKind.STRING || kind == ExtraValueKind.INT || kind == ExtraValueKind.BOOL) {
                obj.optString("value").ifEmpty {
                    throw IllegalArgumentException("ExtraSpec `${key}`: kind `${obj.optString("kind")}` requires `value`")
                }
            } else null
            val map = obj.optJSONObject("map")?.let { m ->
                m.keys().asSequence().associateWith { m.getString(it) }
            }
            val whenExtension = obj.optJSONArray("whenExtension")?.let { arr ->
                (0 until arr.length())
                    .map { arr.getString(it).removePrefix(".").lowercase() }
                    .toSet()
            }
            return ExtraSpec(key, kind, values, value, map, whenExtension)
        }

        private fun parseLaunchMethod(s: String): LaunchMethod = when (s) {
            "intent" -> LaunchMethod.INTENT
            "delfino" -> LaunchMethod.DELFINO
            else -> throw IllegalArgumentException("Unknown launchMethod: `$s`")
        }
    }
}
