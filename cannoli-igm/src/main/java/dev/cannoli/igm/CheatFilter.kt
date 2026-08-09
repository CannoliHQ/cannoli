package dev.cannoli.igm

/** Which of the loaded file's cheats the screen lists. Presentation only: RetroArch is never told. */
enum class CheatFilter {
    ALL, ON, OFF;

    fun shows(enabled: Boolean): Boolean = when (this) {
        ALL -> true
        ON -> enabled
        OFF -> !enabled
    }
}
