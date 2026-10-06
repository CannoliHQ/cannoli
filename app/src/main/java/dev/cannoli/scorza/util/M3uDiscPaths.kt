package dev.cannoli.scorza.util

import java.io.File

fun parseM3uDiscPaths(m3u: File): List<String> = runCatching {
    m3u.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { entry -> File(m3u.parentFile, entry).absolutePath }
}.getOrDefault(emptyList())
