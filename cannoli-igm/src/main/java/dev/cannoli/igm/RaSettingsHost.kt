package dev.cannoli.igm

interface RaSettingsHost {
    fun coreOptions(): List<CoreOptionRef> = emptyList()

    fun systemInfo(): List<Pair<String, String>> = emptyList()

    fun portDeviceTypes(port: Int): PortDevices? = null

    fun setPortDevice(port: Int, id: Int) {}

    fun players(): List<PlayerSlot> = emptyList()

    fun raGetSetting(key: String): RaSetting?

    fun raApply(
        key: String,
        value: MachineValue,
        watch: Collection<String> = emptyList(),
        onDone: (RaApplyResult?) -> Unit = {},
    ): Boolean

    fun flushHeldCommands() {}

    fun raSaveOverride(scope: RaOverrideScope, keys: Set<String>)

    fun saveCannoliOverride(scope: RaOverrideScope, changed: Set<String>) {}

    fun revertCannoliOverride() {}

    fun shadowedSettings(): Map<String, String> = emptyMap()

    fun resetOverrides(scope: RaOverrideScope) {}

    fun hasOverrides(scope: RaOverrideScope): Boolean = false

    fun overlays(): List<String> = emptyList()

    fun shaderEntries(path: List<String>): List<dev.cannoli.core.shader.ShaderEntry> = emptyList()

    fun hasShaders(): Boolean = false

    fun applyShaderPreset(path: List<String>, name: String): Set<String> = emptySet()

    fun appliedShaderPreset(): String? = null

    fun toggleShader() {}

    fun shaderToRestore(): String? = null

    fun setShaderChain(chain: dev.cannoli.core.shader.ShaderPreset?) {}

    fun shaderPresetPath(path: List<String>, name: String): String? = null

    fun applyShaderChain(saveAs: String? = null): String? = null

    fun shaderOverriddenAtGame(): Boolean = false

    fun restoreShaderDefault(): Set<String> = emptySet()

    fun raScreenRows(label: String): List<RaScreenRow> = emptyList()
}
