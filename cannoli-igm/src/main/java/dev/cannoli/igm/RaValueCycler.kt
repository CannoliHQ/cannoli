package dev.cannoli.igm

import java.util.Locale

object RaValueCycler {

    fun next(setting: RaSetting, direction: Int): String? = when (setting.type) {
        RaSettingType.BOOL -> if (setting.value == "true") "false" else "true"
        RaSettingType.ENUM -> {
            val opts = setting.options.orEmpty()
            if (opts.isEmpty()) null
            else {
                val i = opts.indexOf(setting.value)
                if (i < 0) opts.first()
                else opts[((i + direction) % opts.size + opts.size) % opts.size]
            }
        }
        RaSettingType.INT -> stepNumeric(setting, direction)?.let { it.toLong().toString() }
        RaSettingType.FLOAT -> stepNumeric(setting, direction)?.let { formatFloat(it) }
        RaSettingType.STRING_RO -> null
    }

    private fun stepNumeric(setting: RaSetting, direction: Int): Float? {
        val cur = setting.value.toFloatOrNull() ?: return null
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
