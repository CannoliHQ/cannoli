package dev.cannoli.ricotta

/**
 * Mirrors `enum ricotta_osd_type` in ricotta/jni/ricotta_osd.h. The values cross the JNI boundary,
 * so they are fixed on both sides: change one and you change the other.
 */
object RicottaOsdEvent {
    const val SAVE_STATE = 0
    const val LOAD_STATE = 1
    const val RESET = 2
    const val UNDO_SAVE_STATE = 4
    const val DISK_CHANGED = 7
    const val SCREENSHOT = 8
    const val CONTROLLER_PORT = 9
    const val LOAD_REFUSED = 10
    const val HARDCORE_PAUSED = 11
    const val CHEEVOS_LOGIN_FAILED = 12
}
