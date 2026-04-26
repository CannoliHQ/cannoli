package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ArtworkLookup(private val cannoliRoot: File) {
    private val artDir = CannoliPaths(cannoliRoot).artDir
    private val cache = ConcurrentHashMap<String, Map<String, File>>()

    fun find(platformTag: String, basename: String): File? {
        val map = cache.getOrPut(platformTag) { buildMap(platformTag) }
        return map[basename]
    }

    fun invalidate(platformTag: String) {
        cache.remove(platformTag)
    }

    fun invalidateAll() {
        cache.clear()
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
