package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommArtUrl
import dev.cannoli.scorza.romm.RommGame
import java.io.File

/**
 * Manuals come from the RomM instance only, never from the upstream metadata provider. RomM fetches
 * the manual from ScreenScraper server-side, validates it, and serves its own copy at `path_manual`;
 * the `ss_metadata` manual url is the raw ScreenScraper source, which answers a plain 200 with an
 * error page when the media is missing or the quota is spent. The body is still checked before it
 * lands as a guide, since RomM also accepts Markdown manuals the guide viewer cannot render.
 */
object RommManual {

    private const val HEADER_SCAN_BYTES = 1024
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    fun sourceUrl(host: String, game: RommGame): String? =
        game.manualPath
            ?.takeIf { game.hasManual && it.isNotBlank() }
            ?.let { RommArtUrl.resolve(host, it) }

    fun isAvailable(game: RommGame): Boolean = game.hasManual && !game.manualPath.isNullOrBlank()

    fun looksLikePdf(file: File): Boolean {
        if (!file.isFile) return false
        val head = ByteArray(HEADER_SCAN_BYTES)
        val read = file.inputStream().use { it.read(head) }
        if (read < PDF_MAGIC.size) return false
        for (start in 0..read - PDF_MAGIC.size) {
            if (PDF_MAGIC.indices.all { head[start + it] == PDF_MAGIC[it] }) return true
        }
        return false
    }

    /** First bytes of a rejected body, so the log says what the server actually sent. */
    fun describeHead(file: File): String {
        if (!file.isFile) return "(no file)"
        val head = ByteArray(64)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return "(empty)"
        return String(head, 0, read, Charsets.ISO_8859_1)
            .map { if (it.code in 32..126) it else '.' }
            .joinToString("")
    }
}
