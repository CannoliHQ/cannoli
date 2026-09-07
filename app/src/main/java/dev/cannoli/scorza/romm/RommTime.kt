package dev.cannoli.scorza.romm

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Milliseconds from a timestamp RomM sent, or null when it is not one.
 *
 * RomM serializes with Python's `datetime.isoformat()`, which writes the offset out in full,
 * `2026-09-07T14:14:44.123456+00:00`, rather than the `Z` form. `Instant.parse` only learned to
 * accept an offset in JDK 12, and Android 13's java.time predates that, so parsing one that way
 * works on a desktop JVM and fails on the handheld. That is exactly how a conflict screen came to
 * show an unknown server time against a timestamp the server had sent correctly.
 *
 * Offset first because it is the form RomM actually sends; the instant form is kept behind it for
 * anything that hands us a `Z`, including our own `Instant.toString()`.
 */
object RommTime {
    fun millisOrNull(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
