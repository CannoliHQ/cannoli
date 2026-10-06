package dev.cannoli.core.achievements

object RaCacheAge {
    const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    fun isStale(cachedAtMs: Long, nowMs: Long): Boolean = nowMs - cachedAtMs > STALE_AFTER_MS
}
