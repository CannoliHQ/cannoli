package dev.cannoli.ui

enum class ButtonLabelSet {
    PLUMBER, REDMOND, SHAPES, HEDGEHOG_6;

    val south: String get() = when (this) {
        PLUMBER, HEDGEHOG_6 -> "B"; REDMOND -> "A"; SHAPES -> "✕"
    }
    val east: String get() = when (this) {
        PLUMBER, HEDGEHOG_6 -> "A"; REDMOND -> "B"; SHAPES -> "○"
    }
    val west: String get() = when (this) {
        PLUMBER, HEDGEHOG_6 -> "Y"; REDMOND -> "X"; SHAPES -> "□"
    }
    val north: String get() = when (this) {
        PLUMBER, HEDGEHOG_6 -> "X"; REDMOND -> "Y"; SHAPES -> "△"
    }
    val l3: String? get() = if (this == HEDGEHOG_6) "C" else null
    val r3: String? get() = if (this == HEDGEHOG_6) "Z" else null

    companion object {
        fun fromString(value: String?): ButtonLabelSet =
            entries.firstOrNull { it.name == value } ?: PLUMBER
    }
}
