package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ArtworkLookup(private val pathsProvider: CannoliPathsProvider) {
    private val artDir: File get() = CannoliPaths(pathsProvider.root).artDir
    private val cache = ConcurrentHashMap<String, Map<String, File>>()

    fun find(platformTag: String, romFile: File): File? {
        val hit = findByName(platformTag, artBasename(romFile))
        if (hit != null) return hit
        if (romFile.name.endsWith(".p8.png", ignoreCase = true)) return romFile
        return null
    }

    fun findByName(platformTag: String, basename: String): File? =
        cache.getOrPut(platformTag) { buildMap(platformTag) }[basename]

    fun renameArt(platformTag: String, oldBasename: String, newBasename: String): Boolean {
        val existing = findByName(platformTag, oldBasename) ?: return false
        if (findByName(platformTag, newBasename) != null) return false
        val ext = existing.extension
        val target = File(existing.parentFile, if (ext.isEmpty()) newBasename else "$newBasename.$ext")
        val renamed = existing.renameTo(target)
        invalidate(platformTag)
        return renamed
    }

    fun deleteArt(platformTag: String, basename: String): Boolean {
        val existing = findByName(platformTag, basename) ?: return false
        val deleted = existing.delete()
        invalidate(platformTag)
        return deleted
    }

    fun invalidate(platformTag: String) {
        cache.remove(platformTag)
    }

    fun invalidateAll() {
        cache.clear()
    }

    private fun artBasename(romFile: File): String =
        if (romFile.name.endsWith(".p8.png", ignoreCase = true)) {
            romFile.name.dropLast(".p8.png".length)
        } else {
            romFile.nameWithoutExtension
        }

    private fun buildMap(platformTag: String): Map<String, File> {
        val tagDir = File(artDir, platformTag)
        if (!tagDir.exists()) return emptyMap()
        val entries = tagDir.listFiles() ?: return emptyMap()
        val out = mutableMapOf<String, File>()
        for (file in entries) if (file.isFile) out[file.nameWithoutExtension] = file
        return out
    }
}
