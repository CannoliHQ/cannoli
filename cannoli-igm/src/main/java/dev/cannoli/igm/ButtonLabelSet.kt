package dev.cannoli.igm

enum class ButtonLabelSet {
    PLUMBER, REDMOND, SHAPES;

    val confirm: String get() = when (this) {
        PLUMBER -> "A"; REDMOND -> "A"; SHAPES -> "✕"
    }
    val back: String get() = when (this) {
        PLUMBER -> "B"; REDMOND -> "B"; SHAPES -> "○"
    }
    val x: String get() = when (this) {
        PLUMBER -> "X"; REDMOND -> "Y"; SHAPES -> "△"
    }
    val y: String get() = when (this) {
        PLUMBER -> "Y"; REDMOND -> "X"; SHAPES -> "□"
    }

    companion object {
        fun fromString(value: String?): ButtonLabelSet =
            entries.firstOrNull { it.name == value } ?: PLUMBER
    }
}
