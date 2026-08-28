package dev.cannoli.igm

enum class RaSettingType { BOOL, INT, FLOAT, ENUM, STRING_RO }

enum class RaOverrideScope { SYSTEM, GAME }

/**
 * What the core and the config file understand, as opposed to the text a row shows.
 *
 * A type rather than a String so a display label cannot reach a writer: passing one is a compile
 * error instead of a value landing on an option nobody picked.
 */
@JvmInline
value class MachineValue(val raw: String)

/** [display] is translated and can repeat between options, so only [machine] may be compared. */
data class RaOption(val machine: MachineValue, val display: String)

/**
 * [machineValue] is authoritative and [displayValue] is for rendering only.
 *
 * They differ for anything RetroArch renders through get_string_representation: aspect_ratio_index
 * is "22" and "Core Provided". Carrying one string for both is what let a display label reach a
 * writer and a machine value reach a comparison.
 */
data class RaSetting(
    val key: String,
    val label: String,
    val type: RaSettingType,
    val machineValue: MachineValue,
    val displayValue: String,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val options: List<RaOption>? = null,
    val requiresRestart: Boolean = false,
    /** RetroArch's own sublabel, translated by RetroArch and so never entering Crowdin. */
    val description: String? = null,
)

// A row on a RetroArch settings screen: a setting to cycle, or a submenu to descend into.
data class RaScreenRow(
    val key: String,
    val label: String,
    val isMenu: Boolean,
)
