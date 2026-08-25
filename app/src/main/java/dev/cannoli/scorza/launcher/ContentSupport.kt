package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.util.ArchiveExtractor
import java.io.File

/**
 * Whether the core a launch resolved to can read the file it is about to be handed.
 *
 * A core's `supported_extensions` describes what the core parses, not what it can be given.
 * RetroArch unpacks archives and resolves m3u playlists before the core sees anything, which is why
 * `snes9x` plays a zipped ROM while declaring only `smc|sfc|swc|fig|bs|st`, and why 66 of the 83
 * curated cores play multi-disc games without declaring `m3u`. Comparing the file's own extension
 * would refuse both.
 *
 * So the check resolves what the core will actually receive, and refuses only when that is
 * genuinely unreadable. Geolith is the case it exists for: it takes the NeoSD `.neo` format, so a
 * MAME-style Neo Geo romset is not content it can fail to load, it is content it cannot parse.
 */
object ContentSupport {

    private val ARCHIVES = setOf("zip", "7z")
    private val PLAYLISTS = setOf("m3u", "m3u8")

    /**
     * The extension to report, or null when the launch should proceed. Reports the extension of the
     * file the user chose rather than whatever was found inside it, since that is the one they can
     * see and act on.
     *
     * Anything that cannot be resolved returns null. A launch is never blocked on a guess: the cost
     * of a wrong refusal is a game that cannot be started at all, while the cost of letting one
     * through is the failure that would have happened anyway.
     */
    fun unsupported(file: File, supported: Collection<String>): String? {
        if (supported.isEmpty()) return null
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return null
        val effective = when {
            // A playlist always resolves, even for a core that declares m3u, because no core
            // parses one: RetroArch reads it and hands over the first disc. Declaring m3u says the
            // core drives the disk control interface, not that it can read the file.
            ext in PLAYLISTS -> firstEntryExtension(file)
            // An archive the core declares is one it genuinely unpacks itself, the way FinalBurn
            // Neo reads a romset, so that is the content and there is nothing to look inside for.
            ext in supported -> return null
            ext in ARCHIVES -> runCatching { ArchiveExtractor.primaryEntryName(file) }
                .getOrNull()?.let { File(it).extension.lowercase() }
            else -> ext
        } ?: return null
        return if (effective.isEmpty() || effective in supported) null else ext
    }

    /** The extension of the first disc an m3u names. Blank and commented lines are not entries. */
    private fun firstEntryExtension(playlist: File): String? = runCatching {
        playlist.useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { File(it).extension.lowercase() }
        }
    }.getOrNull()
}
