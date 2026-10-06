package dev.cannoli.scorza.romm

import java.time.Instant
import java.time.OffsetDateTime

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
