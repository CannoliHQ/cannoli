package dev.cannoli.scorza.config

import dev.cannoli.core.config.OverrideTiers
import dev.cannoli.core.overlay.OverlayCatalog
import java.io.File

class CannoliPaths(val root: File) {

    constructor(rootPath: String) : this(File(rootPath))

    val artDir: File get() = File(root, "Art")
    val biosDir: File get() = File(root, "BIOS")
    val savesDir: File get() = File(root, "Saves")
    val saveStatesDir: File get() = File(root, "Save States")
    val collectionsDir: File get() = File(root, "Collections")
    val backupDir: File get() = File(root, "Backup")
    val guidesDir: File get() = File(root, "Guides")
    val cheatsDir: File get() = File(root, "Cheats")
    val wallpapersDir: File get() = File(root, "Wallpapers")
    val romsDir: File get() = File(root, "Roms")
    val shadersDir: File get() = File(root, "Shaders")
    val overlaysDir: File get() = File(root, OverlayCatalog.DIR)
    val logsDir: File get() = File(root, "Logs")
    val mediaDir: File get() = File(root, "Media")
    val mediaScreenshotsDir: File get() = File(mediaDir, "Screenshots")
    val mediaRecordingsDir: File get() = File(mediaDir, "Recordings")

    val configDir: File get() = File(root, "Config")
    val configInternal: File get() = File(configDir, "Internal")

    val configState: File get() = File(configInternal, "State")
    val configRetroArch: File get() = File(configInternal, "RetroArch")
    val configCache: File get() = File(configInternal, "Cache")
    val configRetroAchievements: File get() = File(configInternal, "RetroAchievements")
    val configAssets: File get() = File(configInternal, "Assets")

    val configOverrides: File get() = File(configDir, "Overrides")
    val configOverridesSystems: File get() = File(root, OverrideTiers.SYSTEMS_DIR)
    val configOverridesGames: File get() = File(root, OverrideTiers.GAMES_DIR)
    val configScanner: File get() = File(configDir, "Scanner")
    val configFonts: File get() = File(configDir, "Fonts")
    val configLaunchScripts: File get() = File(configDir, "Launch Scripts")
    val configInput: File get() = File(configDir, "Input")

    val configOrdering: File get() = File(configDir, "Ordering")
    val configInputMappings: File get() = File(configInput, "Mappings")
    val configInputAutoconfig: File get() = File(configInput, "Autoconfig")
    val configInputAutoconfigAndroid: File get() = File(configInputAutoconfig, "android")

    val database: File get() = File(configInternal, "cannoli.db")
    val rommDatabase: File get() = File(configInternal, "romm.db")
    val settingsJson: File get() = File(configDir, "settings.json")
    val platformsIni: File get() = File(configDir, "platforms.ini")
    val coresJson: File get() = File(configDir, "cores.json")
    val arcadeMapFile: File get() = File(configScanner, "arcade_map.txt")
    val ignoreExtensionsRoms: File get() = File(configScanner, "ignore_extensions.txt")
    val ignoreFilesRoms: File get() = File(configScanner, "ignore_files.txt")
    val recentlyPlayedFile: File get() = File(configState, "recently_played.txt")
    val guidePositionsFile: File get() = File(configState, "guide_positions.ini")
    val cheatStateFile: File get() = File(configState, "cheat_state.ini")
    val raGameIdsFile: File get() = File(configRetroAchievements, "ra_game_ids.txt")
    val configRaOffline: File get() = File(configRetroAchievements, "Offline")
    val raGameIdsLegacyFile: File get() = File(configRetroArch, "ra_game_ids.txt")
    val raLaunchCfg: File get() = File(configRetroArch, "retroarch_launch.cfg")
    val retroArchCfg: File get() = File(configRetroArch, "retroarch.cfg")
    val customCfg: File get() = File(configDir, "custom.cfg")

    val shortcutsIni: File get() = File(configDir, "shortcuts.ini")
    val globalOverrideCfg: File get() = File(configOverrides, "global.cfg")
    val cannoliFont: File get() = File(configAssets, "cannoli/font.ttf")
    val toolsDir: File get() = File(configLaunchScripts, "Tools")
    val portsDir: File get() = File(configLaunchScripts, "Ports")
    val platformCacheFile: File get() = File(configCache, ".platform_cache.json")
    val gameCacheFile: File get() = File(configCache, ".game_cache")

    fun artFor(tag: String): File = File(artDir, tag)
    fun biosFor(tag: String): File = File(biosDir, tag)
    fun savesFor(tag: String): File = File(savesDir, tag)
    fun saveStatesFor(tag: String): File = File(saveStatesDir, tag)
    fun guidesFor(tag: String): File = File(guidesDir, tag)

    fun cheatsFor(tag: String): File = File(cheatsDir, tag)

    fun saveDirFor(tag: String, romBaseName: String): File =
        File(savesFor(tag), romBaseName)

    fun saveStateGameDir(tag: String, romBaseName: String): File =
        File(saveStatesFor(tag), romBaseName)

    fun saveStateDir(tag: String, romBaseName: String, coreId: String): File =
        File(saveStateGameDir(tag, romBaseName), coreId)

    fun saveStateBase(tag: String, romBaseName: String, coreId: String): File =
        File(saveStateDir(tag, romBaseName, coreId), "$romBaseName.state")

    fun guideDir(tag: String, romBaseName: String): File =
        File(guidesFor(tag), romBaseName)

    fun cheatDir(tag: String, gameTitle: String): File =
        File(cheatsFor(tag), gameTitle)

    fun systemOverrideCfg(tag: String, core: String): File =
        File(File(configOverridesSystems, tag), "$core.cfg")

    fun gameOverrideDir(tag: String, base: String): File =
        File(File(configOverridesGames, tag), base)

    fun gameOverrideCfg(tag: String, base: String, core: String): File =
        File(gameOverrideDir(tag, base), "$core.cfg")

    fun systemSharedCfg(tag: String): File =
        File(File(configOverridesSystems, tag), "${OverrideTiers.SHARED}.cfg")

    fun gameSharedCfg(tag: String, base: String): File =
        File(gameOverrideDir(tag, base), "${OverrideTiers.SHARED}.cfg")

    fun systemOverrideOpt(tag: String, core: String): File =
        File(File(configOverridesSystems, tag), "$core.opt")

    fun gameOverrideOpt(tag: String, base: String, core: String): File =
        File(gameOverrideDir(tag, base), "$core.opt")

    val coreOptionsLaunchOpt: File get() = File(configRetroArch, "core_options_launch.opt")
}
