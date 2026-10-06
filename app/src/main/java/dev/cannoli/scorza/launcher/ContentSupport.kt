package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.util.ArchiveExtractor
import java.io.File

object ContentSupport {

    private val ARCHIVES = setOf("zip", "7z")
    private val PLAYLISTS = setOf("m3u", "m3u8")

    fun unsupported(file: File, supported: Collection<String>): String? {
        if (supported.isEmpty()) return null
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return null
        val effective = when {
            ext in PLAYLISTS -> firstEntryExtension(file)
            ext in supported -> return null
            ext in ARCHIVES -> runCatching { ArchiveExtractor.primaryEntryName(file) }
                .getOrNull()?.let { File(it).extension.lowercase() }
            else -> ext
        } ?: return null
        return if (effective.isEmpty() || effective in supported) null else ext
    }

    private fun firstEntryExtension(playlist: File): String? = runCatching {
        playlist.useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { File(it).extension.lowercase() }
        }
    }.getOrNull()
}
