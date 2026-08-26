package dev.cannoli.scorza.launcher

import java.io.File

/**
 * What the buildbot said when we last downloaded something: the build identity, so we can ask
 * whether it changed, and the build date, so we can say which build the user has.
 *
 * There is nothing to pin to. The buildbot keeps dated APKs but a single unversioned `latest`
 * directory of cores, its stable releases carry no cores at all, and RetroArch's own updater points
 * at the same place. So Cannoli tracks build identity rather than build version.
 *
 * The date is deliberately not the core's `display_version`. That field is hand-maintained upstream
 * and moves on a completely different clock: gambatte last changed it in 2022 while the binary is
 * rebuilt nightly, and mGBA declares `0.10-dev`, which is not a release at all. Showing it would
 * tell two users with builds months apart that they have the same thing.
 *
 * Keyed by URL because that is unambiguous across cores, ABIs and system archives alike.
 */
object DownloadStamps {

    private const val FILE = "download_etags.txt"

    /**
     * [built] is an ISO date, or empty when the server sent no `Last-Modified`. [crc] is the
     * buildbot's CRC32 of the inner `.so`, lowercase hex, or empty when the index did not name it.
     *
     * The etag cannot answer whether a rebuild changed anything: a zip embeds a build timestamp, so
     * it differs every night whether or not the core did. The CRC covers the binary alone and is
     * stable across an unchanged rebuild, which is what makes skipping the download safe.
     */
    data class Stamp(val etag: String, val built: String, val crc: String = "")

    fun read(filesDir: File): Map<String, Stamp> = try {
        File(filesDir, FILE).takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0] to Stamp(
                        parts[1],
                        parts.getOrNull(2).orEmpty(),
                        parts.getOrNull(3).orEmpty(),
                    )
                } else null
            }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun etagFor(filesDir: File, url: String): String? = read(filesDir)[url]?.etag

    fun builtFor(filesDir: File, url: String): String? =
        read(filesDir)[url]?.built?.takeIf { it.isNotBlank() }

    fun crcFor(filesDir: File, url: String): String? =
        read(filesDir)[url]?.crc?.takeIf { it.isNotBlank() }

    @Synchronized
    fun put(filesDir: File, url: String, etag: String?, built: String?, crc: String? = null) {
        if (etag.isNullOrBlank()) return
        // A caller with nothing new to say about the CRC keeps whatever is already recorded, so a
        // 304 that carries no index lookup does not erase it.
        val prior = read(filesDir)
        val keptCrc = crc?.takeIf { it.isNotBlank() } ?: prior[url]?.crc.orEmpty()
        val merged = prior + (url to Stamp(etag, built.orEmpty(), keptCrc))
        try {
            filesDir.mkdirs()
            File(filesDir, FILE).writeText(
                merged.entries.joinToString("\n") {
                    "${it.key}\t${it.value.etag}\t${it.value.built}\t${it.value.crc}"
                }
            )
        } catch (_: Exception) {}
    }

    /** Drops one row, so an uninstalled core does not leave a stamp describing a file that is gone. */
    @Synchronized
    fun remove(filesDir: File, url: String) {
        val prior = read(filesDir)
        if (url !in prior) return
        try {
            File(filesDir, FILE).writeText(
                (prior - url).entries.joinToString("\n") {
                    "${it.key}\t${it.value.etag}\t${it.value.built}\t${it.value.crc}"
                }
            )
        } catch (_: Exception) {}
    }

    /**
     * `Last-Modified` as an ISO date. The header is RFC 1123 in GMT, and only the day is shown, so
     * the wall clock and the reader's zone do not matter.
     */
    fun isoDate(lastModified: String?): String {
        if (lastModified.isNullOrBlank()) return ""
        return try {
            val parsed = java.time.ZonedDateTime.parse(
                lastModified.trim(),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            )
            parsed.toLocalDate().toString()
        } catch (_: Exception) {
            ""
        }
    }
}
