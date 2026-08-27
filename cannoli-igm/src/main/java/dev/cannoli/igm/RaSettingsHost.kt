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

    /**
     * Puts back anything the host applied by means other than writing a setting.
     *
     * Rewriting a value is not always enough to undo it. A shader is compiled into the render chain
     * by a separate call, so restoring video_shader leaves the old one still running: the config
     * would say one thing and the screen show another.
     */
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

    /**
     * One level of the shader browser, folders first then presets, already filtered to what the
     * running video driver can load. [path] is empty for the root.
     */
    fun shaderEntries(path: List<String>): List<dev.cannoli.core.shader.ShaderEntry> = emptyList()

    /**
     * Whether the shader tree has anything the running driver can load. Separate from
     * [shaderEntries] because the root asks this on every render and the answer must not cost a
     * walk of the database to produce.
     */
    fun hasShaders(): Boolean = false

    /**
     * Applies the preset [name] from [path] and returns the RetroArch keys it wrote, so the caller
     * can stage them into the save prompt without knowing what a shader is.
     *
     * Applied on a press rather than as the highlight moves: a shader compiles when it loads, so
     * previewing every row on the way past would stall the list.
     */
    fun applyShaderPreset(path: List<String>, name: String): Set<String> = emptySet()

    /** Absolute path of the preset in force, so the browser can say which row is applied. */
    fun appliedShaderPreset(): String? = null

    /** Whether the shader in force is this game's own choice rather than its platform's. */
    fun shaderOverriddenAtGame(): Boolean = false

    /**
     * Drops this game's shader override and loads what the platform says instead, returning the
     * keys it staged. Absent at platform scope, where there is nothing above to fall back to.
     */
    fun restoreShaderDefault(): Set<String> = emptySet()

    /**
     * Lets the game run while a menu is open, so a change to the picture can actually be seen.
     *
     * The menu pauses emulation and a paused RetroArch presents no frames, so a shader applied here
     * would not appear until the menu closed. Muted while it runs, and the host restores both the
     * pause and the previous mute when this goes off.
     */
    fun setLivePreview(on: Boolean) {}

    /** One RetroArch settings screen. An empty label is the root. */
    fun raScreenRows(label: String): List<RaScreenRow> = emptyList()
    fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit)
}
