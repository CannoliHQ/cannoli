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
     * Persists values Cannoli owns rather than RetroArch, at the scope the save prompt chose. They
     * are not RetroArch settings, so the native writer that pulls live values by key cannot reach
     * them and the host writes its own tier entry.
     *
     * [changed] is the same set the RetroArch save is given, so a host writes only what this visit
     * actually touched. Writing unconditionally copies a value the user never edited into whichever
     * scope they happened to pick for something else.
     */
    fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {}

    /** Puts those same values back, for Discard. */
    fun revertCannoliOverride() {}

    /**
     * RetroArch settings Cannoli has taken over for its own use, mapped to the value the user actually
     * chose. The live value belongs to Cannoli, so the menu must read and resolve against these instead,
     * or it will treat Cannoli's value as unrecognised state and normalise it away.
     */
    fun shadowedSettings(): Map<String, String> = emptyMap()

    /**
     * Overlay folder names for this platform, in display order, empty when it has none. Cannoli
     * owns these rather than RetroArch, so they are a host question rather than a setting.
     */
    fun overlays(): List<String> = emptyList()

    /** One RetroArch settings screen. An empty label is the root. */
    fun raScreenRows(label: String): List<RaScreenRow> = emptyList()
    fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit)
}
