package dev.cannoli.scorza.updater

/**
 * [manifestField] is the release manifest's own name for the channel, not an identity: the setting
 * is stored by [name], and writing the other one reads back as STABLE with no error.
 */
enum class ReleaseChannel(val manifestField: String, val label: String) {
    STABLE("stable", "Stable"),
    BETA("beta", "Beta"),
    TEST("test", "Test");

    fun visibleChannels(): List<ReleaseChannel> = entries.filter { it.ordinal <= ordinal }

    companion object {
        fun fromString(value: String?): ReleaseChannel =
            entries.firstOrNull { it.name == value } ?: STABLE
    }
}
