package dev.cannoli.igm

interface RaSettingsHost {
    /** Options the running core exposes, in the order the core declares them. */
    fun coreOptions(): List<CoreOptionRef> = emptyList()

    /** Label and value pairs describing the running core, in display order. Empty when unavailable. */
    fun systemInfo(): List<Pair<String, String>> = emptyList()

    fun raGetSetting(key: String): RaSetting?
    fun raSetSetting(key: String, value: String): Boolean
    fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>)

    /**
     * RetroArch settings Cannoli has taken over for its own use, mapped to the value the user actually
     * chose. The live value belongs to Cannoli, so the menu must read and resolve against these instead,
     * or it will treat Cannoli's value as unrecognised state and normalise it away.
     */
    fun shadowedSettings(): Map<String, String> = emptyMap()

    /** One RetroArch settings screen. An empty label is the root. */
    fun raScreenRows(label: String): List<RaScreenRow> = emptyList()
    fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit)
}
