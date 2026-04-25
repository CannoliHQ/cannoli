package dev.cannoli.scorza.util

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NameMapLookup(cannoliRoot: File) {
    private val arcadeMapFile = File(cannoliRoot, "Config/arcade_map.txt")
    private val cache = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var arcadeCache: Map<String, String>? = null

    fun mapFor(dir: File, fallbackToArcade: Boolean = false): Map<String, String> {
        val key = dir.absolutePath
        val mapFile = File(dir, "map.txt")
        if (mapFile.exists()) {
            cache[key]?.let { if (it !== arcadeCache) return it }
            return parse(mapFile).also { cache[key] = it }
        }
        if (fallbackToArcade && arcadeMapFile.exists()) {
            if (arcadeCache == null) arcadeCache = parse(arcadeMapFile)
            cache[key] = arcadeCache!!
            return arcadeCache!!
        }
        return emptyMap()
    }

    fun invalidate(dir: File) {
        cache.remove(dir.absolutePath)
    }

    fun invalidateAll() {
        cache.clear()
        arcadeCache = null
    }

    private fun parse(file: File): Map<String, String> {
        return try {
            file.readLines()
                .filter { '\t' in it }
                .associate { line ->
                    val (filename, displayName) = line.split('\t', limit = 2)
                    filename.trim() to displayName.trim()
                }
        } catch (_: IOException) { emptyMap() }
    }
}
