package dev.cannoli.igm

enum class CheatFilter {
    ALL, ON, OFF;

    fun shows(enabled: Boolean): Boolean = when (this) {
        ALL -> true
        ON -> enabled
        OFF -> !enabled
    }
}
