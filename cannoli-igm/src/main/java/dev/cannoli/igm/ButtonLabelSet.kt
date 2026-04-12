package dev.cannoli.igm

enum class ButtonLabelSet {
    PLUMBER, REDMOND, SHAPES;

    val south: String get() = when (this) {
        PLUMBER -> "B"; REDMOND -> "A"; SHAPES -> "✕"
    }
    val east: String get() = when (this) {
        PLUMBER -> "A"; REDMOND -> "B"; SHAPES -> "○"
    }
    val west: String get() = when (this) {
        PLUMBER -> "Y"; REDMOND -> "X"; SHAPES -> "□"
    }
    val north: String get() = when (this) {
        PLUMBER -> "X"; REDMOND -> "Y"; SHAPES -> "△"
    }

    companion object {
        fun fromString(value: String?): ButtonLabelSet =
            entries.firstOrNull { it.name == value } ?: PLUMBER
    }
}
