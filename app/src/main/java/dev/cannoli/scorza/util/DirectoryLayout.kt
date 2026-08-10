package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.artTag
import java.io.File

private const val CUSTOM_CFG_BANNER = "# This file is yours. Cannoli never overwrites it. Keys here win.\n"

object DirectoryLayout {
    fun ensure(cannoliRoot: File, romDirectory: File, assets: AssetManager, platformConfig: PlatformConfig) {
        val paths = CannoliPaths(cannoliRoot)
        listOf(
            romDirectory,
            paths.artDir,
            paths.biosDir,
            paths.savesDir,
            paths.saveStatesDir,
            paths.mediaScreenshotsDir,
            paths.mediaRecordingsDir,
            paths.configDir,
            paths.configState,
            paths.configRetroArch,
            paths.configOverrides,
            paths.configOverridesSystems,
            paths.configOverridesGames,
            paths.backupDir,
            paths.guidesDir,
            paths.cheatsDir,
            paths.wallpapersDir,
        ).forEach { it.mkdirs() }

        val arcadeMap = paths.arcadeMapFile
        if (!arcadeMap.exists()) {
            try {
                assets.open("arcade_map.txt").use { input ->
                    arcadeMap.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {}
        }

        val customCfg = paths.customCfg
        if (!customCfg.exists()) {
            try {
                customCfg.writeText(CUSTOM_CFG_BANNER)
            } catch (_: Exception) {}
        }

        val tags = platformConfig.getAllTags()
        if (romDirNeedsScaffold(romDirectory)) {
            scaffoldRomFolders(romDirectory, tags)
        }
        seedRomFolderOnce(romDirectory, paths.configState, "PC")
        for (tag in tags) {
            paths.artFor(tag).mkdirs()
            paths.biosFor(tag).mkdirs()
            paths.savesFor(tag).mkdirs()
            paths.saveStatesFor(tag).mkdirs()
            paths.guidesFor(tag).mkdirs()
            paths.cheatsFor(tag).mkdirs()
        }
        for (type in AppType.entries) {
            paths.artFor(type.artTag).mkdirs()
        }
    }

    // Tags added after a user's install was scaffolded never get a Roms folder, because the
    // scaffold only runs on an empty Roms directory. Seed such a tag once, keyed by a marker so
    // a folder the user then deletes stays deleted.
    fun seedRomFolderOnce(romDirectory: File, stateDir: File, tag: String): Boolean {
        val marker = File(stateDir, ".seeded_$tag")
        if (marker.exists()) return false
        val created = File(romDirectory, tag).mkdirs()
        try {
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        } catch (_: Exception) {}
        return created
    }

    fun romDirNeedsScaffold(romDirectory: File): Boolean =
        romDirectory.listFiles()?.any { it.isDirectory && !it.name.startsWith(".") } != true

    fun scaffoldRomFolders(romDirectory: File, tags: Collection<String>): Int {
        var created = 0
        for (tag in tags) {
            if (File(romDirectory, tag).mkdirs()) created++
        }
        return created
    }

    fun resetCustomCfg(customCfg: File) {
        customCfg.writeText(CUSTOM_CFG_BANNER)
    }
}
