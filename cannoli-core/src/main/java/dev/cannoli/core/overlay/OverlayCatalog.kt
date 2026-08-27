package dev.cannoli.core.overlay

import java.io.File

// Overlays live one folder deep under Overlays/<tag>/, so a downloaded pack drops in whole and the
// folder name is what a person picks from. Cannoli draws the artwork itself rather than handing it
// to RetroArch's input_overlay, which is a touch-input subsystem Cannoli has no use for, so nothing
// here reads or writes a RetroArch overlay cfg: a folder is a name and a picture.
object OverlayCatalog {

    private const val IMAGE_EXT = "png"

    // Both processes resolve this directory: the launcher to list and choose, the in-game menu to
    // draw. Named here so neither spells it out for itself.
    const val DIR = "Overlays"

    fun platformDir(cannoliRoot: File, tag: String): File = File(File(cannoliRoot, DIR), tag)

    /** Folder names holding usable artwork, in display order. */
    fun list(platformDir: File, log: (String) -> Unit = {}): List<String> {
        if (!platformDir.isDirectory) return emptyList()
        foldLooseImages(platformDir, log)
        val folders = platformDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return folders
            .filter { resolveImage(it) != null }
            .map { it.name }
            .sortedBy { it.lowercase() }
    }

    /** The artwork drawn for a folder. Lowest name wins, so a multi-image pack resolves the same twice. */
    fun resolveImage(folder: File): File? =
        folder.listFiles()
            ?.filter { it.isFile && it.extension.equals(IMAGE_EXT, ignoreCase = true) }
            ?.minByOrNull { it.name.lowercase() }

    // v1 kept bare images directly under Overlays/<tag>/. Each becomes its own folder so the rest of
    // this file has one shape to read, mirroring how RomDirectoryWalker folders a loose cue set.
    private fun foldLooseImages(platformDir: File, log: (String) -> Unit) {
        val loose = platformDir.listFiles()
            ?.filter { it.isFile && it.extension.equals(IMAGE_EXT, ignoreCase = true) }
            ?: return
        for (image in loose) {
            val target = File(platformDir, image.nameWithoutExtension)
            if (target.exists()) {
                log("overlays: skip ${image.name} (${target.name} already exists)")
                continue
            }
            if (!target.mkdirs()) {
                log("overlays: skip ${image.name} (could not create ${target.name})")
                continue
            }
            if (move(image, File(target, image.name))) {
                log("overlays: foldered ${image.name} into ${target.name}")
            } else {
                target.delete()
                log("overlays: skip ${image.name} (move failed)")
            }
        }
    }

    // renameTo fails across FUSE mounts on some SD cards even within one directory, so a failed
    // rename falls back to a copy rather than leaving the image where the scan will retry it.
    private fun move(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            from.delete()
        } catch (_: Exception) {
            to.delete()
            false
        }
    }
}
