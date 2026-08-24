package dev.cannoli.scorza.config

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.ui.screens.CoreAvailability
import dev.cannoli.scorza.ui.screens.EmulatorMappingStatus
import dev.cannoli.core.IniData
import dev.cannoli.core.IniParser
import dev.cannoli.scorza.util.sortedNatural
import org.json.JSONObject
import java.io.File

private const val UNGROUPED = "Other"

class PlatformConfig(
    private val cannoliRootProvider: () -> File,
    private val assets: AssetManager,
    private val coreInfo: CoreInfoRepository? = null,
    private val nativeLibDir: String? = null,
    // Resolved string passed in because this class has no Context; defaulted for tests.
    private val emptyOverrideLabel: String = "(empty override)",
    private val needsSetupLabel: String = "Needs setup",
    private val internalLabel: String = "Internal",
    private val standaloneLabel: String = "Standalone",
    /**
     * Migration only. Before the source split, which RetroArch ran a mapping was a single global
     * setting rather than part of the mapping. Null means that setting named the in-APK RetroArch,
     * so stored RetroArch mappings migrate to [EmulatorSource.Embedded]; a package name means they
     * were running that external install and migrate to [EmulatorSource.RetroArch] carrying it.
     */
    private val legacyExternalRaPackage: () -> String? = { null },
) {

    constructor(
        cannoliRoot: File,
        assets: AssetManager,
        coreInfo: CoreInfoRepository? = null,
        nativeLibDir: String? = null,
    ) : this({ cannoliRoot }, assets, coreInfo, nativeLibDir)

    private var defaultCores = mapOf<String, String>()
    private var defaultPlatformNames = mapOf<String, String>()

    /**
     * Manufacturer per platform, from platforms.json. The launcher is the source of truth so
     * Kitchen can read it rather than keeping a second list that drifts every time a platform is
     * added on one side only.
     */
    private var platformGroups = mapOf<String, String>()

    /**
     * Group order, taken from where each group first appears in platforms.json rather than a second
     * list to keep in step. "Other" is forced last: it is a catch-all, and the asset's order puts
     * it wherever its first member happens to sit.
     */
    private var platformGroupOrder = listOf<String>()

    /**
     * Platform order within a group, taken from platforms.json, where the tags are listed by
     * release year. Sorting the rows by name instead would read as alphabetical, which is not how
     * anyone thinks about a console line.
     */
    private var platformTagOrder = mapOf<String, Int>()
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
        val groups = mutableMapOf<String, String>()
        val apps = mutableMapOf<String, List<AppConfig>>()
        val arcade = mutableSetOf<String>()
        for (tag in json.keys()) {
            val entry = json.getJSONObject(tag)
            entry.optString("name", "").takeIf { it.isNotEmpty() }?.let { names[tag] = it }
            entry.optString("core", "").takeIf { it.isNotEmpty() }?.let { cores[tag] = it }
            entry.optString("group", "").takeIf { it.isNotEmpty() }?.let { groups[tag] = it }
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
        platformGroups = groups
        platformGroupOrder = groups.values.distinct()
            .sortedBy { if (it == UNGROUPED) 1 else 0 }
        platformTagOrder = groups.keys.withIndex().associate { (i, tag) -> tag to i }
        defaultApps = apps
        arcadePlatforms = arcade
    }

    private var ini: IniData = IniData(emptyMap())
    private var userChoices: MutableMap<String, EmulatorChoice> = java.util.concurrent.ConcurrentHashMap()
    // v1 per-game overrides, keyed by absolute path, held only until the boot-time migration
    // moves them into the game_overrides table where they are keyed by rom_id.
    private var pendingV1Overrides: MutableMap<String, EmulatorChoice> = java.util.concurrent.ConcurrentHashMap()
    // True while reading a file written before RetroArch choices carried their package, which is
    // the only situation in which a packageless RetroArch choice needs reinterpreting.
    private var legacyFile = false
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
            // v3 is the first version whose RetroArch choices name their package. Anything older
            // needs the source split applied on read; anything at v3 is already correct, and
            // re-running the migration would rewrite a deliberate packageless choice.
            legacyFile = version < CORES_JSON_VERSION
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
        val source = readSource(obj.optString("source", "")) ?: return null
        val coreId = obj.optString("core", "")
        val app = obj.optString("app", "").ifEmpty { null }
        // A core source with no core names nothing, so it cannot render or launch. Dropping it
        // leaves the platform unset, which the boot seed then refills with the built-in default.
        if (source != EmulatorSource.Standalone && coreId.isEmpty()) {
            dev.cannoli.scorza.util.ErrorLog.write("dropping $source choice with no core")
            return null
        }
        if (source == EmulatorSource.Standalone && app == null) return null
        return resolveLegacyRetroArch(EmulatorChoice(source = source, coreId = coreId, appPackage = app))
    }

    /**
     * v2 files written before the built-in libretro runner was removed still name "Internal" as
     * their source. valueOf would fail on it and the whole platform mapping would be dropped, so
     * the retired name is folded onto the embedded RetroArch that replaced that runner.
     */
    private fun readSource(name: String): EmulatorSource? =
        if (name == EmulatorSource.RETIRED_INTERNAL_SOURCE) EmulatorSource.Embedded
        else runCatching { EmulatorSource.valueOf(name) }.getOrNull()

    /**
     * Resolves a stored [EmulatorSource.RetroArch] choice, which predates the source split and so
     * does not say which RetroArch it meant. Everything else passes through untouched.
     */
    private fun resolveLegacyRetroArch(choice: EmulatorChoice): EmulatorChoice {
        if (!legacyFile) return choice
        if (choice.source != EmulatorSource.RetroArch || choice.appPackage != null) return choice
        val external = legacyExternalRaPackage()
            ?: return choice.copy(source = EmulatorSource.Embedded)
        return choice.copy(appPackage = external)
    }

    // v1 stored the picker's display caption as the runner, so the source has to be recovered
    // from it. An absent runner means v1 was re-deriving the source from the bundled .so on
    // every read, so resolve it here the same way getRunnerLabel did.
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

    /**
     * Moves v1 path-keyed overrides into the rom_id keyed table, then drops the section by
     * rewriting the file as v2.
     *
     * These win over whatever the SQLite cutover imported, because every override written since
     * that import went only to cores.json. Entries whose ROM is no longer indexed are dropped.
     */
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
            else -> EmulatorSource.RetroArch
        }
        if (source == EmulatorSource.Standalone) return EmulatorChoice(source, coreId, app)
        // v1's setCoreMapping dropped the core when the pick equalled the platform default but
        // still wrote the runner, so an Internal/RetroArch entry can arrive with no core at all.
        // That is v1 saying "the default core", not "no core". Standalone is excluded above: its
        // identity is the package, and stamping a core on it would misreport the mapping.
        val resolved = coreId.ifEmpty { fallbackCoreId.orEmpty() }
        if (resolved.isEmpty()) return null
        return resolveLegacyRetroArch(EmulatorChoice(source, resolved, app))
    }

    fun reloadCoreMappings() {
        loadCoreMappings()
    }

    fun saveCoreMappings() {
        // A failed load leaves the maps empty. Writing that out would turn a recoverable parse
        // error into permanent data loss, so refuse until something loads cleanly.
        if (loadFailed) {
            dev.cannoli.scorza.util.ErrorLog.write("cores.json save skipped: last load failed")
            return
        }
        // v2 holds platform mappings only. Per-game overrides live in the game_overrides table,
        // keyed by rom_id so they survive a rename, move or auto-organize.
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

    /**
     * Gives every platform Cannoli can serve itself an explicit mapping, so no screen has to
     * fall back to a derived default.
     *
     * Only ever writes a platform that has no stored choice. It never overwrites, repairs or
     * clears one, including when the stored core or app is no longer installed. Being idempotent
     * it also backfills a platform that gains a bundled core in a later release, and recovers
     * from a first run that was interrupted part way through.
     */
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
        // Exactly one, never the first of several. Picking among several installed emulators is
        // the silent-guess behavior this rework exists to remove.
        val installed = getAppOptions(tag).filter { pm.isPackageInstalled(it.packageName) }
        if (installed.size == 1) {
            return EmulatorChoice(EmulatorSource.Standalone, appPackage = installed.single().packageName)
        }
        return null
    }

    fun getPlatformChoice(tag: String): EmulatorChoice? = userChoices[tag]

    // Writes unconditionally. The old setCoreMapping dropped a pick that equalled the current
    // default, so a release that changed that default silently changed the user's platform.
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

    /** Renders a choice for a list row. Used for per-game override rows and the context menu. */
    fun describeChoice(choice: EmulatorChoice, pm: PackageManager? = null, raLabel: String = "RetroArch"): String =
        when (choice.source) {
            EmulatorSource.Standalone -> {
                val pkg = choice.appPackage ?: return emptyOverrideLabel
                pm?.let { resolveAppLabel(it, pkg) } ?: (knownAppLabels[pkg] ?: pkg)
            }
            EmulatorSource.Embedded ->
                "${internalLabel}: ${getCoreDisplayName(choice.coreId)}"
            EmulatorSource.RetroArch ->
                "${raPackageLabel(choice.appPackage, raLabel)}: ${getCoreDisplayName(choice.coreId)}"
        }

    /** The caption for an external RetroArch, taken from the package the choice names. */
    private fun raPackageLabel(pkg: String?, fallback: String): String =
        pkg?.let { InstalledCoreService.getPackageLabel(it) } ?: fallback

    /**
     * Restores the platform to the same choice a first run would have seeded: the bundled core
     * when there is one, otherwise the sole installed standalone app, otherwise nothing.
     *
     * Resolves immediately rather than clearing and leaving the next boot's backfill to fill it
     * in, so what the user sees after confirming is the final state.
     */
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

    fun getAllTags(): Set<String> = defaultPlatformNames.keys + ini.getSection("platforms").keys

    /** Sort key for a group, so a list reads in the asset's order with the catch-all last. */
    fun groupRank(group: String?): Int =
        if (group == null) Int.MAX_VALUE else platformGroupOrder.indexOf(group).takeIf { it >= 0 } ?: Int.MAX_VALUE

    /** Position in platforms.json, which lists each group's tags by release year. */
    fun tagRank(tag: String): Int =
        platformTagOrder[tag.uppercase(java.util.Locale.ROOT)] ?: Int.MAX_VALUE

    /** Null for a tag the bundled definitions do not group, which callers show as ungrouped. */
    fun getGroup(tag: String): String? = platformGroups[tag.uppercase(java.util.Locale.ROOT)]

    /** Group per tag, for the tags asked about. Absent tags are simply not in the result. */
    fun getGroups(tags: Collection<String>): Map<String, String> =
        tags.mapNotNull { tag -> getGroup(tag)?.let { tag to it } }.toMap()

    fun getAppPackage(tag: String): String? =
        userChoices[tag]?.appPackage ?: defaultApps[tag.uppercase()]?.firstOrNull()?.packageName

    // The explicit standalone pick only, with no fall back to the list default, so launch can
    // tell "the user chose this app" apart from "this app happens to be listed first".
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

    fun getFirmwareStatus(coreId: String, biosDir: File): List<Pair<FirmwareEntry, Boolean>> {
        val all = coreInfo?.getFirmwareFor(coreId) ?: emptyList()
        return all.map { it to File(biosDir, it.path).exists() }
    }

    // The caption is derived, never stored, so a choice that names a different RetroArch package
    // updates every label instead of leaving stale ones behind.
    fun getRunnerLabel(tag: String, coreId: String, raLabel: String = "RetroArch"): String {
        if (File(romsTagDir(tag), ".emu_launch").exists()) return "External"
        val choice = userChoices[tag]
        return when (choice?.source ?: EmulatorSource.Embedded) {
            EmulatorSource.Embedded -> internalLabel
            EmulatorSource.Standalone -> standaloneLabel
            EmulatorSource.RetroArch -> raPackageLabel(choice?.appPackage, raLabel)
        }
    }

    fun getDetailedMappings(
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unreportablePackages: Set<String> = emptySet(),
        raLabel: String = "RetroArch",
    ): List<dev.cannoli.scorza.ui.screens.EmulatorMappingEntry> {
        val tags = (defaultCores.keys + defaultApps.keys + userChoices.keys)
        return tags.map { tag ->
            detailedMappingFor(tag, pm, installedRaCores, embeddedCoresDir, unreportablePackages, raLabel)
        }.sortedNatural { it.platformName }
    }

    fun detailedMappingFor(
        tag: String,
        pm: PackageManager? = null,
        installedRaCores: Map<String, Set<String>> = emptyMap(),
        embeddedCoresDir: String? = null,
        unreportablePackages: Set<String> = emptySet(),
        raLabel: String = "RetroArch",
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
            val resolvedRunner = getRunnerLabel(tag, coreId, raLabel)
            val status = coreStatus(tag, coreId, resolvedRunner, installedRaCores, embeddedCoresDir, unreportablePackages)
            // "Missing" is a confirmed absence: an internal .so that is not on disk, or a
            // RetroArch that reported its cores and did not name this one. "Unknown" means no
            // report is possible, so the row must not claim either way.
            val mappingStatus = when (status) {
                "Missing" -> EmulatorMappingStatus.NOT_INSTALLED
                "Unknown" -> EmulatorMappingStatus.UNKNOWN
                else -> EmulatorMappingStatus.READY
            }
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
        installedRaCores: Map<String, Set<String>>,
        embeddedCoresDir: String?,
        unreportablePackages: Set<String>
    ): String {
        if (runner == internalLabel) {
            val dir = embeddedCoresDir ?: nativeLibDir ?: return "Missing"
            return if (File(dir, "${coreId}_android.so").exists()) "Present" else "Missing"
        }
        if (runner == "External") return "Present"
        if (installedRaCores.any { it.value.contains(coreId) }) return "Present"
        if (unreportablePackages.isNotEmpty()) return "Unknown"
        return "Missing"
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
            // Embedded is offered whenever the platform has candidate cores, present or not: a
            // missing core is downloadable into the in-APK RetroArch, so absence is not a reason
            // to hide the runner that can fix it.
            if (hasCoreCandidates) add(EmulatorSource.Embedded)
            if (hasCoreCandidates) add(EmulatorSource.RetroArch)
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
        externalRaPackages: List<String> = emptyList(),
        unreportableRaPackages: Set<String> = emptySet(),
    ): List<dev.cannoli.scorza.ui.screens.EmulatorPickerOption> {
        val upper = tag.uppercase()
        val candidateCoreIds = buildSet {
            defaultCores[upper]?.let { add(it) }
            coreInfo?.getCoresForTag(tag)?.forEach { add(it.id) }
        }
        val embeddedDir = embeddedCoresDir ?: nativeLibDir
        return when (source) {
            // The in-APK RetroArch loads from filesDir/cores, so presence is a file check rather
            // than a query. It never reports "unknown": the directory is always readable.
            EmulatorSource.Embedded -> candidateCoreIds.mapNotNull { coreId ->
                val present = embeddedDir != null && File(embeddedDir, "${coreId}_android.so").exists()
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
            // One row per (core, install) pair. Two RetroArch installs that both carry a core are
            // two distinct choices, and the choice records which one, so nothing depends on a
            // global "the configured RetroArch" any more.
            EmulatorSource.RetroArch -> externalRaPackages.flatMap { pkg ->
                val reported = installedRaCores[pkg].orEmpty()
                val cannotReport = pkg in unreportableRaPackages
                candidateCoreIds.mapNotNull { coreId ->
                    val label = InstalledCoreService.getPackageLabel(pkg)
                    when {
                        coreId in reported -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                            coreId = coreId, displayName = getCoreDisplayName(coreId),
                            source = EmulatorSource.RetroArch, runnerLabel = label, appPackage = pkg,
                        )
                        // Installed-only is not a filter that can be computed against a package
                        // that reports nothing, so the section ignores includeAll rather than
                        // filtering every candidate away and reading as "you have no cores".
                        cannotReport -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                            coreId = coreId, displayName = getCoreDisplayName(coreId),
                            source = EmulatorSource.RetroArch, runnerLabel = label, appPackage = pkg,
                            availability = CoreAvailability.UNKNOWN,
                        )
                        includeAll -> dev.cannoli.scorza.ui.screens.EmulatorPickerOption(
                            coreId = coreId, displayName = getCoreDisplayName(coreId),
                            source = EmulatorSource.RetroArch, runnerLabel = label, appPackage = pkg,
                            availability = CoreAvailability.UNAVAILABLE,
                        )
                        else -> null
                    }
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

    // The curated name wins over the installed app's own label, which is often decorated
    // (MMJR2 ships as "Dolphin |MMJR2|") or inconsistent with how the app is known.
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
        "com.emulator.fpse" to "FPse",
        "com.emulator.fpse64" to "FPse64",
        "org.ppsspp.ppsspp" to "PPSSPP",
        "org.ppsspp.ppssppgold" to "PPSSPP Gold",
        "xyz.aethersx2.android" to "NetherSX2",
        "org.dolphinemu.dolphinemu" to "Dolphin",
        "org.dolphinemu.mmjr" to "Dolphin MMJR2",
        "dev.cannoli.delfino" to "Delfino",
        "dev.cannoli.delfino.debug" to "Delfino (Debug)",
        "org.azahar_emu.azahar" to "Azahar",
        "info.cemu.cemu" to "Cemu",
        "org.vita3k.emulator" to "Vita3K",
        "aenu.aps3e" to "aPS3e",
        "ru.vastness.altmer.iratajaguar" to "IrataJaguar",
        "com.fms.colem.deluxe" to "ColEm Deluxe",
        "com.fms.colem" to "ColEm",
        "com.sky.SkyEmu" to "SkyEmu",
        "com.pixelrespawn.linkboy" to "Linkboy",
        "it.dbtecno.pizzaboyscpro" to "Pizza Boy SC Pro",
        "it.dbtecno.pizzaboyscbasic" to "Pizza Boy SC Basic",
        "com.hydra.noods" to "NooDS",
        "me.magnum.melondualds" to "melonDS DualDS",
        "com.armsx2" to "ARMSX2",
        "dev.eden.eden_emulator" to "Eden",
        "dev.eden.eden_emulator.nightly" to "Eden Nightly",
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

    // One resolver. These used to diverge: the UI read user-pick then default while launch read
    // user-pick then platforms.ini [cores] then default, so a [cores] entry made the mapping
    // screen describe one core while a different one launched.
    fun getCoreName(tag: String): String? = getCoreMapping(tag).ifEmpty { null }

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
        /** v3 added the package to a RetroArch choice, replacing the global package setting. */
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
            "shell" -> LaunchMethod.SHELL
            "delfino" -> LaunchMethod.DELFINO
            else -> throw IllegalArgumentException("Unknown launchMethod: `$s`")
        }
    }
}
