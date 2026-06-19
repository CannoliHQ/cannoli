package dev.cannoli.igm

enum class RaSettingType { BOOL, INT, FLOAT, ENUM, STRING_RO }

enum class RaOverrideScope { CONTENT_DIR, GAME }

data class RaSetting(
    val key: String,
    val label: String,
    val type: RaSettingType,
    val value: String,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val options: List<String>? = null,
    val requiresRestart: Boolean = false,
)
