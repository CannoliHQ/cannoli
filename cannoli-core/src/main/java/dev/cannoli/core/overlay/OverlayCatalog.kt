package dev.cannoli.core.overlay

import java.io.File

object OverlayCatalog {

    private const val IMAGE_EXT = "png"

    const val DIR = "Overlays"

    fun platformDir(cannoliRoot: File, tag: String): File = File(File(cannoliRoot, DIR), tag)

    fun list(platformDir: File, log: (String) -> Unit = {}): List<String> {
        if (!platformDir.isDirectory) return emptyList()
        foldLooseImages(platformDir, log)
        val folders = platformDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return folders
            .filter { resolveImage(it) != null }
            .map { it.name }
            .sortedBy { it.lowercase() }
    }

    fun resolveImage(folder: File): File? =
        folder.listFiles()
            ?.filter { it.isFile && it.extension.equals(IMAGE_EXT, ignoreCase = true) }
            ?.minByOrNull { it.name.lowercase() }

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
