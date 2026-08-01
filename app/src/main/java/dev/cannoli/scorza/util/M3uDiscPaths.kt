package dev.cannoli.scorza.util

import java.io.File

/** Parses an m3u playlist into the ordered absolute paths of the disc files it references. */
fun parseM3uDiscPaths(m3u: File): List<String> = runCatching {
    m3u.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { entry -> File(m3u.parentFile, entry).absolutePath }
}.getOrDefault(emptyList())
