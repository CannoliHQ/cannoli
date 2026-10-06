package dev.cannoli.scorza.input.autoconfig

class BundledAutoconfigEntries(private val eager: List<RetroArchCfgEntry>) {

    fun entries(): List<RetroArchCfgEntry> = eager

    companion object {
        fun forTest(entries: List<RetroArchCfgEntry>): BundledAutoconfigEntries =
            BundledAutoconfigEntries(entries)
    }
}
