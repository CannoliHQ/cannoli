package dev.cannoli.scorza.util

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaScannerConnection
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.artTag
import java.io.File

private const val CUSTOM_CFG_BANNER = "# This file is yours. Cannoli never overwrites it. Keys here win.\n"
private const val SEED_RECORD_FILE = "seeded_platforms.txt"
private const val SEED_BASELINE_ASSET = "seed_baseline.txt"

object DirectoryLayout {
    fun ensure(
        cannoliRoot: File,
        romDirectory: File,
        assets: AssetManager,
        platformConfig: PlatformConfig,
        context: Context? = null,
    ) {
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
            paths.configInternal,
            paths.configScanner,
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
            writeSeedRecord(paths.configState, tags)
        } else {
            seedNewRomFolders(romDirectory, paths.configState, tags, assets)
        }
        hideFromGallery(paths.artDir)
        for (tag in tags) {
            paths.artFor(tag).also { it.mkdirs(); hideFromGallery(it) }
            paths.biosFor(tag).mkdirs()
            paths.savesFor(tag).mkdirs()
            paths.saveStatesFor(tag).mkdirs()
            paths.guidesFor(tag).mkdirs()
            paths.cheatsFor(tag).mkdirs()
        }
        for (type in AppType.entries) {
            paths.artFor(type.artTag).also { it.mkdirs(); hideFromGallery(it) }
        }
        if (context != null) forgetIndexedArt(context, paths.artDir)
    }

    private fun forgetIndexedArt(context: Context, artDir: File) {
        val marker = File(artDir, ".rescanned_for_nomedia")
        if (marker.exists() || !artDir.isDirectory) return
        try {
            MediaScannerConnection.scanFile(context, arrayOf(artDir.absolutePath), null, null)
            marker.createNewFile()
        } catch (_: Exception) {}
    }

    fun hideFromGallery(dir: File) {
        val marker = File(dir, ".nomedia")
        if (marker.exists()) return
        try {
            dir.mkdirs()
            marker.createNewFile()
        } catch (_: Exception) {}
    }

    fun seedNewRomFolders(
        romDirectory: File,
        stateDir: File,
        tags: Collection<String>,
        assets: AssetManager,
    ): Int {
        val seeded = readSeedRecord(stateDir) ?: legacyBaseline(stateDir, assets) ?: return 0
        var created = 0
        for (tag in tags) {
            if (tag !in seeded && File(romDirectory, tag).mkdirs()) created++
        }
        writeSeedRecord(stateDir, seeded + tags)
        return created
    }

    private fun legacyBaseline(stateDir: File, assets: AssetManager): Set<String>? {
        val shipped = try {
            assets.open(SEED_BASELINE_ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        } catch (_: Exception) {
            return null
        }
        val markers = stateDir.listFiles()
            ?.filter { it.name.startsWith(".seeded_") }
            ?.map { it.name.removePrefix(".seeded_") }
            .orEmpty()
        return shipped.toSet() + markers
    }

    private fun readSeedRecord(stateDir: File): Set<String>? {
        val file = File(stateDir, SEED_RECORD_FILE)
        if (!file.isFile) return null
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSeedRecord(stateDir: File, tags: Collection<String>) {
        try {
            stateDir.mkdirs()
            File(stateDir, SEED_RECORD_FILE).writeText(tags.toSortedSet().joinToString("\n"))
        } catch (_: Exception) {}
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
