package dev.cannoli.scorza.romm

enum class RommCollectionGroup(val apiPath: String) {
    USER("/api/collections"),
    VIRTUAL("/api/collections/virtual"),
    SMART("/api/collections/smart"),
}
