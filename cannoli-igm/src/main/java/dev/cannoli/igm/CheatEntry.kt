package dev.cannoli.igm

data class CheatEntry(
    val desc: String = "",
    val code: String = "",
    val enable: Boolean = false,
    val handler: Int = HANDLER_EMU,
    val bigEndian: Boolean = false,
    val cheatType: Int = TYPE_SET_TO_VALUE,
    val memorySearchSize: Int = 3,
    val value: Long = 0,
    val address: Long = 0,
    val addressBitPosition: Long = 0,
    val repeatCount: Long = 1,
    val repeatAddToValue: Long = 0,
    val repeatAddToAddress: Long = 1,
    val rumbleType: Long = 0,
    val rumbleValue: Long = 0,
    val rumblePort: Long = 0,
    val rumblePrimaryStrength: Long = 0,
    val rumblePrimaryDuration: Long = 0,
    val rumbleSecondaryStrength: Long = 0,
    val rumbleSecondaryDuration: Long = 0,
) {
    val displayLabel: String get() = desc.ifBlank { code }
    val isEmuHandler: Boolean get() = handler == HANDLER_EMU

    companion object {
        const val HANDLER_EMU = 0
        const val HANDLER_RETRO = 1
        const val TYPE_DISABLED = 0
        const val TYPE_SET_TO_VALUE = 1
        const val TYPE_INCREASE_VALUE = 2
        const val TYPE_DECREASE_VALUE = 3
        const val TYPE_RUN_NEXT_IF_EQ = 4
        const val TYPE_RUN_NEXT_IF_NEQ = 5
        const val TYPE_RUN_NEXT_IF_LT = 6
        const val TYPE_RUN_NEXT_IF_GT = 7
    }
}
