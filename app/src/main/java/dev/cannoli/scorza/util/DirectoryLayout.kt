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
        }
        seedRomFolderOnce(romDirectory, paths.configState, "PC")
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

    /**
     * Drop art the gallery already indexed. The marker only stops the next scan; it does not touch
     * rows MediaStore already holds, and a library scraped before the marker existed is entirely
     * such rows, so on an upgrade the covers stay in the user's photos until something asks
     * MediaStore to look again.
     *
     * One scan of the root is enough: the scanner walks the tree, finds the marker, and removes the
     * entries beneath it rather than merely skipping them. Verified on device.
     */
    private fun forgetIndexedArt(context: Context, artDir: File) {
        val marker = File(artDir, ".rescanned_for_nomedia")
        if (marker.exists() || !artDir.isDirectory) return
        try {
            MediaScannerConnection.scanFile(context, arrayOf(artDir.absolutePath), null, null)
            marker.createNewFile()
        } catch (_: Exception) {}
    }

    /**
     * Keep box art out of the gallery. Without this every cover Cannoli downloads turns up in the
     * user's photos, mixed in with their camera roll.
     *
     * Written into each platform folder as well as the root, rather than relying on the root alone
     * to cover the tree, because a folder can outlive the marker above it: a sync tool or a manual
     * copy that recreates `Art` without it would expose every platform underneath at once.
     */
    fun hideFromGallery(dir: File) {
        val marker = File(dir, ".nomedia")
        if (marker.exists()) return
        try {
            dir.mkdirs()
            marker.createNewFile()
        } catch (_: Exception) {}
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
