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

// Matches the line ricotta_ra_save_override writes on the tiers it owns, so every generated
// override file says the same thing about who wrote it and where a user's own keys belong.
private const val ASSET_SHADERS = "shaders"
// The name Nonna's Kitchen already skips when listing a resource folder, so a stamp does not
// show up as a file someone put there.
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
    private val portRouter: dev.cannoli.scorza.input.runtime.PortRouter,
    private val installedCoreService: InstalledCoreService? = null,
    private val gameOverrides: dev.cannoli.scorza.db.GameOverrideStore? = null,
    private val globalOverrides: dev.cannoli.scorza.settings.GlobalOverridesManager? = null,
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
     * Copies Cannoli's own shader presets onto the card.
     *
     * Only what Cannoli authored ships in the APK; everything else comes from the libretro shader
     * database through Update Shaders. Both formats are copied because which one loads depends on
     * the video driver a platform is running, and only the menu knows that.
     *
     * Keyed on the app's version rather than on the files existing, so an upgrade carrying a fixed
     * preset actually replaces the old one. It copies rather than syncs: anything downloaded or
     * hand-placed beside it is left alone, since this owns its own folders and nothing else.
     */
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

    /**
     * Fingerprint of the shipped shader assets.
     *
     * Keyed on the files rather than on the app's version, because the version does not change
     * between development builds: an edited preset would sit in the APK while the card kept the
     * copy from the first install, and the card's copy is the one RetroArch loads. A hash notices
     * any edit, and costs a read of about seventy kilobytes once per boot.
     */
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
            // Sorted, so the same tree hashes the same however the platform happens to list it.
            children.sorted().forEach { digestAssetTree("$assetPath/$it", md) }
        }
    }

    /**
     * Builds the shader index if the tree has one to describe and does not already have it.
     *
     * Normally an extraction writes it, but a tree that predates the index, or one assembled by
     * hand, would otherwise make the browser walk thousands of folders on every level. Cheap when
     * the index exists, which is the usual case, and the walk itself happens once.
     */
    fun ensureShaderIndex(root: File) {
        val dir = CannoliPaths(root).shadersDir
        if (!dir.isDirectory) return
        if (ShaderIndex.file(dir).isFile) return
        ShaderIndex.build(dir)
    }

    // Returns false on the first failure rather than leaving a half-copied preset stamped as done,
    // which would then be skipped on every later boot.
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

    /**
     * Writes the config the embedded runner loads, and records its path for [buildGameConfig] to
     * compose the override tiers onto.
     *
     * Regenerated on every launch rather than seeded once, so an install upgraded from an older
     * build gets today's default keys immediately instead of carrying whatever it had on disk. It
     * was called syncRetroArchConfig back when it copied from a separately installed RetroArch;
     * nothing external is involved now, and the old name read as dead code that could be deleted.
     */
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
        // The preference stack, weakest to strongest: global, this platform, this one game, then
        // the user's own custom.cfg. The plumbing band applied below always wins over all of them -
        // see applyOverrides below and #36 in the launch config design. Each of the two middle
        // scopes is a pair: a core-independent tier under the core-keyed one. Keying by core is
        // right for values that describe how a core behaves, since run-ahead compensates a specific
        // core's internal latency, and it is wrong for values that describe how a platform or game
        // should look - an overlay is the same choice whichever core runs it, so a core-keyed tier
        // would silently drop it on a remap. Core-specific sits above core-independent within a
        // scope because it is the narrower statement of the two.
        writeGlobalDefaults(paths)
        val preferenceBase = RetroArchConfigComposer.compose(
            baseConfig,
            listOf(
                readOverrideLayer(paths.globalOverrideCfg),
                readOverrideLayer(paths.systemSharedCfg(rom.platformTag)),
                readOverrideLayer(paths.systemOverrideCfg(rom.platformTag, core)),
                readOverrideLayer(paths.gameSharedCfg(rom.platformTag, romName)),
                readOverrideLayer(paths.gameOverrideCfg(rom.platformTag, romName, core)),
                readOverrideLayer(paths.customCfg),
            ),
        )
        val stateDir = paths.saveStateDir(rom.platformTag, romName, core)
        stateDir.mkdirs()
        val saveDir = paths.savesFor(rom.platformTag)
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
            // Where RetroArch writes the preset that compiling a chain produces, and where it
            // looks for one by name. Left unset it is empty, and applying a chain built in the
            // menu silently writes nowhere and shows nothing.
            put("video_shader_dir", paths.shadersDir.absolutePath)
            // Core options are not RetroArch settings and cannot live in this config, so they are
            // composed into their own file from the same platform and game tiers. global_core_options
            // is what makes RetroArch use core_options_path verbatim instead of deriving a per-core
            // path of its own, which has no platform or game dimension at all.
            put("core_options_path", composeCoreOptions(paths, rom.platformTag, romName, core))
            put("global_core_options", "true")
            // A core that shuts down must return to Cannoli, not to RetroArch. Left at its own
            // default, RetroArch loads the dummy core instead of exiting, and a dummy core with no
            // content has nothing to draw but RetroArch's own menu: that is how pressing a button on
            // FBNeo's missing-firmware screen landed in Load Core and the Online Updater. The Core
            // settings screen is already hidden below, but hiding the screen never set the value.
            put("load_dummy_on_core_shutdown", "false")
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

    fun resolveLaunchFile(rom: Rom, extractArchives: Boolean): File? {
        if (extractArchives && ArchiveExtractor.isArchive(rom.path) && !platformConfig.isArcade(rom.platformTag)) {
            return ArchiveExtractor.extract(rom.path, context.cacheDir, rom.path.nameWithoutExtension)
        }
        return rom.path
    }

    /**
     * The core this ROM would run on: its own override first, then the platform's mapping. Save
     * states are keyed by it, so the picker and the launch have to agree on the answer.
     */
    fun coreForStates(rom: Rom): String? =
        overrideFor(rom)?.coreId?.ifEmpty { null } ?: platformConfig.getCoreName(rom.platformTag)

    /** The stored source that applies to this ROM, game override first. Null means unset. */
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

    // Nothing stored resolves to the embedded runner, which is the only one always present.
    private fun usesEmbeddedRetroArch(rom: Rom): Boolean =
        (sourceFor(rom) ?: EmulatorSource.Embedded) == EmulatorSource.Embedded

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
            // Only decides whether to fall back to a standalone app when nothing is
            // stored, so it asks the one runner that can load a core.
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
                // The embedded runner was never asked whether it holds the core, so a
                // mapping naming one it does not have launched RetroArch and failed
                // inside it. Migrated mappings make that the common case rather than a
                // corner, since they name cores this runner has never downloaded.
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
                // App-only platform (no core) that reached here has nothing installed to
                // run it, so name the app it wants instead of a core it never had.
                val appPkg = platformConfig.getAppPackage(rom.platformTag)
                if (appPkg != null && !context.isPackageInstalled(appPkg)) {
                    LaunchResult.AppNotInstalled(appPkg)
                } else {
                    LaunchResult.NoEmulatorSet
                }
            }
        }

        // Carries the same attribution the early returns above use: a launch that failed on a
        // per-game override must send recovery to that override, not to the platform mapping.
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
                else LaunchResult.NoEmulatorSet,
                rom.platformTag, rom.id,
            )
        }
        val core = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
            ?: run { launchState.launching = false; launchState.lastLaunched = null; return null }
        // Resume used to fire and forget: it discarded the LaunchResult and returned without
        // clearing launching, so a failed startActivity never reached onResume and every later
        // launch became a silent no-op. It now runs the same pre-checks and funnel as launchRom.
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

    /**
     * Required BIOS the platform does not have, so a launch can be stopped before the emulator sits
     * on a missing file. Requiredness is corrected by `bios_required.txt` where a core's own flags
     * cannot express it: FBNeo marks every entry optional, which is right for arcade romsets and
     * wrong for Neo Geo.
     */
    /**
     * The file's extension when the resolved core has no parser for it, null when the launch should
     * go ahead. Runs ahead of the BIOS gate: sending someone to find a BIOS for a pairing that could
     * never load is worse than saying the pairing is wrong.
     */
    private fun unsupportedExtension(launchFile: File, core: String): String? =
        ContentSupport.unsupported(launchFile, platformConfig.coreExtensions(core))

    /**
     * Installed means complete: the `.so` on disk and the system files that download alongside it.
     * A core missing either cannot run, and both are Cannoli's to deliver, so one screen and one
     * outlet serve them. Bundled sets are not consulted, since the APK carries them and
     * [SystemFiles.ensureBundled] lays them down on this same path.
     */
    private fun coreIsComplete(core: String, tag: String): Boolean {
        val service = installedCoreService ?: return true
        if (core !in service.embeddedCores()) return false
        val biosDir = CannoliPaths(File(settings.sdCardRoot)).biosFor(tag)
        return SystemFiles.remoteSetsPresent(context.assets, core, tag, biosDir)
    }

    /**
     * The BIOS directory as a core will see it. Bundled system files land on first use, and they
     * have to land before anything reads the directory: the gate runs ahead of the launch config,
     * and PSP declares `PPSSPP/ppge_atlas.zim` required firmware while shipping it inside
     * `PPSSPP.zip`. Extracting any later would block a platform on a file the APK is carrying for
     * it. Every later call is a marker read.
     */
    private fun prepareBios(tag: String, dir: File): File {
        dir.mkdirs()
        SystemFiles.ensureBundled(context.assets, tag, dir, apkStamp(context))
        return dir
    }

    private fun missingRequiredBios(tag: String, core: String): List<String> {
        val biosDir = prepareBios(tag, CannoliPaths(File(settings.sdCardRoot)).biosFor(tag))
        return platformConfig.getFirmwareStatus(tag, core, biosDir)
            .filter { (entry, present) -> !entry.optional && !present }
            .map { (entry, _) -> File(entry.path).name }
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
                // getApplicationLabel cannot resolve a package that is not installed, and this
                // branch only fires when it is not, so use the curated label like MissingCore does.
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
            builtinPorts = builtinPorts(),
            // Dropped here rather than in the emulator process: an action bound to nothing is one
            // the matcher can never match, and carrying it only widens the key set native watches.
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

    // Keycodes bound to BTN_MENU in the active mapping open the Cannoli IGM in ricotta,
    // mirroring how the launcher's own in-game menu opens. Falls back to the platform
    // default (BACK + BUTTON_MODE) when no mapping is active.
    // A launch-time snapshot, which is the whole of it: a built-in pad is by definition present
    // before the game starts, so anything arriving later is a real connection worth announcing.
    private fun builtinPorts(): List<Int> =
        portRouter.snapshotEntries()
            .filter { it.mapping.match.builtin ?: it.device.isBuiltIn }
            .mapNotNull { it.port }
            .distinct()
            .sorted()

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
            // Android defaults this on, and the config is rewritten every launch, so the
            // last-extracted version never matches and RetroArch unpacks the APK's assets again on
            // every run. Its completion callback issues a full driver reinit, which cost a visible
            // hitch about a second in. Nothing reads the extracted copies: assets_directory below
            // points elsewhere and the unpack lands in the app data dir.
            appendLine("bundle_assets_extract_enable = \"false\"")
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

        // Identifies the installed build. Both the bundled cores and the bundled system files key
        // their extraction markers on it, so an upgrade re-lays what it ships and nothing else.
        // Falls back rather than throwing: this sits on the launch path, and a build we cannot
        // identify is a reason to skip an extraction, never a reason to fail the launch.
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
