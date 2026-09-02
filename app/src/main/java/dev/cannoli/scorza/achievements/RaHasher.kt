package dev.cannoli.scorza.achievements

/**
 * RetroAchievements' ROM hash, or null when this build cannot compute one.
 *
 * The library is loaded into a flag rather than an init block. An init block throws at whatever
 * first touches the object, outside any try this object owns, so a missing library escaped as an
 * ExceptionInInitializerError, was caught far upstream as a generic failure, and got reported to
 * the user as a network problem. Loading it this way keeps the promise the signature makes: a
 * question this cannot answer comes back null.
 */
object RaHasher {
    private val available: Boolean = try {
        System.loadLibrary("cannoli_hash")
        true
    } catch (_: Throwable) {
        false
    }

    fun hashRom(path: String, consoleId: Int): String? {
        if (!available) return null
        return try {
            nativeHashRom(path, consoleId).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private external fun nativeHashRom(path: String, consoleId: Int): String
}
