package dev.cannoli.scorza.db

enum class CollectionType {
    STANDARD,
    FAVORITES;

    companion object {
        fun fromColumn(value: String): CollectionType =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
