package dev.cannoli.scorza.updater

enum class ReleaseChannel(val key: String, val label: String) {
    STABLE("stable", "Stable"),
    BETA("beta", "Beta"),
    TEST("test", "Test");

    fun visibleChannels(): List<ReleaseChannel> = entries.filter { it.ordinal <= ordinal }

    companion object {
        fun fromString(value: String?): ReleaseChannel =
            entries.firstOrNull { it.name == value } ?: STABLE
    }
}
