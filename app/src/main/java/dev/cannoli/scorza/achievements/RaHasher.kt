package dev.cannoli.scorza.achievements

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
