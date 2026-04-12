package dev.cannoli.igm

enum class ConfirmButton {
    SOUTH, EAST;

    companion object {
        fun fromString(value: String?): ConfirmButton =
            entries.firstOrNull { it.name == value } ?: EAST
    }
}
