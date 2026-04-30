package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import java.io.File

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
            paths.configOverridesCores,
            paths.configOverridesSystems,
            paths.configOverridesGames,
            paths.configInputMappings,
            paths.backupDir,
            paths.guidesDir,
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

        for (tag in platformConfig.getAllTags()) {
            File(romDirectory, tag).mkdirs()
            paths.artFor(tag).mkdirs()
            paths.biosFor(tag).mkdirs()
            paths.savesFor(tag).mkdirs()
            paths.saveStatesFor(tag).mkdirs()
            paths.guidesFor(tag).mkdirs()
        }
    }
}
