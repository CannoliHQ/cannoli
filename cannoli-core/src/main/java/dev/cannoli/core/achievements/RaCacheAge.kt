package dev.cannoli.core.achievements

/**
 * When a cached set is old enough to say so.
 *
 * Write-through means a game's cache age is the time since it was last played online, so a stale
 * entry is one nobody has played in a month rather than a failure. It is surfaced, never hidden or
 * refreshed behind the user's back.
 */
object RaCacheAge {
    const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    fun isStale(cachedAtMs: Long, nowMs: Long): Boolean = nowMs - cachedAtMs > STALE_AFTER_MS
}
