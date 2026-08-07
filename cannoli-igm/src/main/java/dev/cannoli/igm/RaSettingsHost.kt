package dev.cannoli.igm

interface RaSettingsHost {
    /** Options the running core exposes, in the order the core declares them. */
    fun coreOptions(): List<CoreOptionRef> = emptyList()

    fun raGetSetting(key: String): RaSetting?
    fun raSetSetting(key: String, value: String): Boolean
    fun raSaveOverride(scope: RaOverrideScope)
    fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit)
    fun getLocalToggle(key: String, default: Boolean): Boolean
    fun setLocalToggle(key: String, value: Boolean)
}
