package dev.cannoli.scorza.launcher

import java.io.File

object DownloadStamps {

    private const val FILE = "download_etags.txt"

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
