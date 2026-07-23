package dev.cannoli.scorza.ra

object RaHasher {
    fun hashRom(path: String, consoleId: Int): String? =
        try {
            nativeHashRom(path, consoleId).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }

    private external fun nativeHashRom(path: String, consoleId: Int): String

    init {
        System.loadLibrary("retro_bridge")
    }
}
