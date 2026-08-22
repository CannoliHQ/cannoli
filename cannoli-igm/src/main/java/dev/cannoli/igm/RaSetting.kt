package dev.cannoli.igm

enum class RaSettingType { BOOL, INT, FLOAT, ENUM, STRING_RO }

enum class RaOverrideScope { SYSTEM, GAME }

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
    // The machine value, where `value` may be display text. An ENUM renders through RetroArch's
    // get_string_representation, so aspect_ratio_index reads "Core Provided" in `value` and "22"
    // here. Anything comparing values must use this: display text is translated, can repeat across
    // options, and deriving a number from it means enumerating labels, which writes the live
    // setting once per option. Null for core options, which do not carry one.
    val rawValue: String? = null,
    // RetroArch's own explanation of the setting, from its sublabel. Translated by RetroArch, so it
    // never enters Crowdin. Null when the setting has none.
    val description: String? = null,
)
