package dev.cannoli.igm

interface RaSettingsHost {
    /** Keys of the options the running core exposes, already prefixed for [raGetSetting]. */
    fun coreOptionKeys(): List<String> = emptyList()

    fun raGetSetting(key: String): RaSetting?
    fun raSetSetting(key: String, value: String): Boolean
    fun raSaveOverride(scope: RaOverrideScope)
    fun setOnRaSettingApplied(callback: (key: String, value: String) -> Unit)
    fun getLocalToggle(key: String, default: Boolean): Boolean
    fun setLocalToggle(key: String, value: Boolean)
}
