package dev.cannoli.igm

import java.util.Locale

object RaValueCycler {

    fun next(setting: RaSetting, direction: Int): MachineValue? = when (setting.type) {
        RaSettingType.BOOL ->
            MachineValue(if (setting.machineValue.raw == "true") "false" else "true")
        RaSettingType.ENUM -> {
            val opts = setting.options.orEmpty()
            if (opts.isEmpty()) null
            else {
                val i = opts.indexOfFirst { it.machine == setting.machineValue }
                if (i < 0) opts.first().machine
                else opts[((i + direction) % opts.size + opts.size) % opts.size].machine
            }
        }
        RaSettingType.INT -> stepNumeric(setting, direction)?.let { MachineValue(it.toLong().toString()) }
        RaSettingType.FLOAT -> stepNumeric(setting, direction)?.let { MachineValue(formatFloat(it)) }
        RaSettingType.STRING_RO -> null
    }

    private fun stepNumeric(setting: RaSetting, direction: Int): Float? {
        val cur = setting.machineValue.raw.toFloatOrNull() ?: return null
        val step = setting.step?.takeIf { it > 0f } ?: 1f
        var next = cur + direction * step
        setting.min?.let { if (next < it) next = it }
        setting.max?.let { if (next > it) next = it }
        return next
    }

    private fun formatFloat(v: Float): String {
        val s = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }
}
