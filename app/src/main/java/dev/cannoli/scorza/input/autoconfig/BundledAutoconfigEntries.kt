package dev.cannoli.scorza.input.autoconfig

// Bundled cfgs are loaded synchronously at first injection. The earlier async-on-Dispatchers.IO
// design produced a race between controller enumeration and bundled-cfg loading: when a device
// enumerated before the IO load completed, MappingResolver's candidate matching found nothing and
// the resolver found no match and reported the pad as unidentified, sending a perfectly known
// controller to the setup wizard. Synchronous load costs some startup latency but guarantees the cfg
// database is available whenever any code asks for it.

class BundledAutoconfigEntries(private val eager: List<RetroArchCfgEntry>) {

    fun entries(): List<RetroArchCfgEntry> = eager

    companion object {
        fun forTest(entries: List<RetroArchCfgEntry>): BundledAutoconfigEntries =
            BundledAutoconfigEntries(entries)
    }
}
