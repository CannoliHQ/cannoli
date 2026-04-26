package dev.cannoli.scorza.config

import java.io.File

class CannoliPaths(val root: File) {

    constructor(rootPath: String) : this(File(rootPath))

    // Top-level data directories
    val artDir: File get() = File(root, "Art")
    val biosDir: File get() = File(root, "BIOS")
    val savesDir: File get() = File(root, "Saves")
    val saveStatesDir: File get() = File(root, "Save States")
    val collectionsDir: File get() = File(root, "Collections")
    val backupDir: File get() = File(root, "Backup")
    val guidesDir: File get() = File(root, "Guides")
    val wallpapersDir: File get() = File(root, "Wallpapers")
    val romsDir: File get() = File(root, "Roms")
    val shadersDir: File get() = File(root, "Shaders")
    val overlaysDir: File get() = File(root, "Overlays")
    val logsDir: File get() = File(root, "Logs")
    val mediaDir: File get() = File(root, "Media")
    val mediaScreenshotsDir: File get() = File(mediaDir, "Screenshots")
    val mediaRecordingsDir: File get() = File(mediaDir, "Recordings")

    // Config tree
    val configDir: File get() = File(root, "Config")
    val configState: File get() = File(configDir, "State")
    val configRetroArch: File get() = File(configDir, "RetroArch")
    val configOverrides: File get() = File(configDir, "Overrides")
    val configOverridesCores: File get() = File(configOverrides, "Cores")
    val configOverridesSystems: File get() = File(configOverrides, "systems")
    val configOverridesGames: File get() = File(configOverrides, "Games")
    val configCache: File get() = File(configDir, "Cache")
    val configProfiles: File get() = File(configDir, "Profiles")
    val configFonts: File get() = File(configDir, "Fonts")
    val configOrdering: File get() = File(configDir, "Ordering")
    val configLaunchScripts: File get() = File(configDir, "Launch Scripts")
    val configRetroAchievements: File get() = File(configDir, "RetroAchievements")
    val configAssets: File get() = File(configDir, "Assets")

    // Specific config files
    val database: File get() = File(configDir, "cannoli.db")
    val settingsJson: File get() = File(configDir, "settings.json")
    val platformsIni: File get() = File(configDir, "platforms.ini")
    val coresJson: File get() = File(configDir, "cores.json")
    val arcadeMapFile: File get() = File(configDir, "arcade_map.txt")
    val ignoreExtensionsRoms: File get() = File(configDir, "ignore_extensions_roms.txt")
    val ignoreFilesRoms: File get() = File(configDir, "ignore_files_roms.txt")
    val recentlyPlayedFile: File get() = File(configState, "recently_played.txt")
    val quickResumeFile: File get() = File(configState, "quick_resume.txt")
    val guidePositionsFile: File get() = File(configState, "guide_positions.ini")
    val raGameIdsFile: File get() = File(configRetroAchievements, "ra_game_ids.txt")
    val raGameIdsLegacyFile: File get() = File(configRetroArch, "ra_game_ids.txt")
    val raLaunchCfg: File get() = File(configRetroArch, "retroarch_launch.cfg")
    val cannoliFont: File get() = File(configAssets, "cannoli/font.ttf")
    val toolsDir: File get() = File(configLaunchScripts, "Tools")
    val portsDir: File get() = File(configLaunchScripts, "Ports")
    val platformCacheFile: File get() = File(configCache, ".platform_cache.json")
    val gameCacheFile: File get() = File(configCache, ".game_cache")

    // Per-tag helpers
    fun artFor(tag: String): File = File(artDir, tag)
    fun biosFor(tag: String): File = File(biosDir, tag)
    fun savesFor(tag: String): File = File(savesDir, tag)
    fun saveStatesFor(tag: String): File = File(saveStatesDir, tag)
    fun guidesFor(tag: String): File = File(guidesDir, tag)
    fun overlaysFor(tag: String): File = File(overlaysDir, tag)
    fun romsFor(tag: String): File = File(romsDir, tag)

    // Per-game helpers
    fun saveStateDir(tag: String, romBaseName: String): File =
        File(saveStatesFor(tag), romBaseName)

    fun saveStateBase(tag: String, romBaseName: String): File =
        File(saveStateDir(tag, romBaseName), "$romBaseName.state")

    fun guideDir(tag: String, gameTitle: String): File =
        File(guidesFor(tag), gameTitle)

    // Profiles & overrides
    fun profileFile(name: String): File = File(configProfiles, "$name.ini")
    fun systemOverrideFile(tag: String): File = File(configOverridesSystems, "$tag.ini")
    fun gameOverrideFile(tag: String, gameBaseName: String): File =
        File(File(configOverridesGames, tag), "$gameBaseName.ini")

    // Logs
    fun coreLogDir(coreName: String): File = File(logsDir, coreName)
}
