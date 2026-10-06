package dev.cannoli.igm

enum class RaSettingType { BOOL, INT, FLOAT, ENUM, STRING_RO }

enum class RaOverrideScope { SYSTEM, GAME }

@JvmInline
value class MachineValue(val raw: String)

data class RaOption(val machine: MachineValue, val display: String)

data class RaApplyResult(val value: MachineValue, val moved: Map<String, MachineValue> = emptyMap())

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
    val description: String? = null,
)

data class RaScreenRow(
    val key: String,
    val label: String,
    val isMenu: Boolean,
)
