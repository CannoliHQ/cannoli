package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.config.PlatformConfig
import java.io.File

object DirectoryLayout {
    fun ensure(cannoliRoot: File, romDirectory: File, assets: AssetManager, platformConfig: PlatformConfig) {
        listOf(
            romDirectory,
            File(cannoliRoot, "Art"),
            File(cannoliRoot, "Collections"),
            File(cannoliRoot, "BIOS"),
            File(cannoliRoot, "Saves"),
            File(cannoliRoot, "Save States"),
            File(cannoliRoot, "Media/Screenshots"),
            File(cannoliRoot, "Media/Recordings"),
            File(cannoliRoot, "Config"),
            File(cannoliRoot, "Config/Ordering"),
            File(cannoliRoot, "Config/State"),
            File(cannoliRoot, "Config/RetroArch"),
            File(cannoliRoot, "Config/Cache"),
            File(cannoliRoot, "Config/Overrides"),
            File(cannoliRoot, "Config/Overrides/Cores"),
            File(cannoliRoot, "Config/Overrides/systems"),
            File(cannoliRoot, "Config/Overrides/Games"),
            File(cannoliRoot, "Backup"),
            File(cannoliRoot, "Guides"),
            File(cannoliRoot, "Wallpapers"),
        ).forEach { it.mkdirs() }

        val arcadeMap = File(cannoliRoot, "Config/arcade_map.txt")
        if (!arcadeMap.exists()) {
            try {
                assets.open("arcade_map.txt").use { input ->
                    arcadeMap.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {}
        }

        for (tag in platformConfig.getAllTags()) {
            File(romDirectory, tag).mkdirs()
            File(cannoliRoot, "Art/$tag").mkdirs()
            File(cannoliRoot, "BIOS/$tag").mkdirs()
            File(cannoliRoot, "Saves/$tag").mkdirs()
            File(cannoliRoot, "Save States/$tag").mkdirs()
            File(cannoliRoot, "Guides/$tag").mkdirs()
        }
    }
}
