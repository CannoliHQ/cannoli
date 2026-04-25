package dev.cannoli.scorza.db

import dev.cannoli.scorza.util.ScanLog

enum class CollectionType {
    STANDARD,
    FAVORITES;

    companion object {
        fun fromColumn(value: String): CollectionType =
            entries.firstOrNull { it.name == value } ?: run {
                ScanLog.write("WARN unknown collection_type '$value', defaulting to STANDARD")
                STANDARD
            }
    }
}
