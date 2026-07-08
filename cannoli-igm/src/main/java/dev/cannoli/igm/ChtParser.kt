package dev.cannoli.igm

object ChtParser {

    fun parse(text: String): List<CheatEntry> {
        val values = mutableMapOf<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            values[key] = value
        }
        val count = values["cheats"]?.let(::parseNumber)?.toInt() ?: return emptyList()
        if (count <= 0) return emptyList()
        return (0 until count).map { i -> entryAt(values, i) }
    }

    private fun entryAt(values: Map<String, String>, i: Int): CheatEntry {
        fun str(key: String) = values["cheat${i}_$key"]
        fun num(key: String, default: Long) = str(key)?.let(::parseNumber) ?: default
        fun bool(key: String) = str(key)?.let { it == "true" || it == "1" } ?: false
        return CheatEntry(
            desc = str("desc") ?: "",
            code = str("code") ?: "",
            enable = bool("enable"),
            handler = num("handler", 0).toInt(),
            bigEndian = bool("big_endian"),
            cheatType = num("cheat_type", 1).toInt(),
            memorySearchSize = num("memory_search_size", 3).toInt(),
            value = num("value", 0),
            address = num("address", 0),
            addressBitPosition = num("address_bit_position", 0),
            repeatCount = num("repeat_count", 1),
            repeatAddToValue = num("repeat_add_to_value", 0),
            repeatAddToAddress = num("repeat_add_to_address", 1),
            rumbleType = num("rumble_type", 0),
            rumbleValue = num("rumble_value", 0),
            rumblePort = num("rumble_port", 0),
            rumblePrimaryStrength = num("rumble_primary_strength", 0),
            rumblePrimaryDuration = num("rumble_primary_duration", 0),
            rumbleSecondaryStrength = num("rumble_secondary_strength", 0),
            rumbleSecondaryDuration = num("rumble_secondary_duration", 0),
        )
    }

    private fun parseNumber(raw: String): Long {
        val s = raw.trim()
        val hex = s.startsWith("0x", ignoreCase = true)
        val digits = if (hex) s.substring(2) else s
        val radix = if (hex) 16 else 10
        var end = 0
        while (end < digits.length && Character.digit(digits[end], radix) >= 0) end++
        if (end == 0) return 0
        return try {
            digits.substring(0, end).toLong(radix)
        } catch (_: NumberFormatException) {
            0
        }
    }
}
