package dev.cannoli.scorza.input.autoconfig

import java.io.InputStream

object RetroArchCfgParser {

    private val LINE_REGEX = Regex("""^\s*input_([a-z0-9_]+)\s*=\s*"([^"]*)"\s*$""")

    fun parse(source: String): RetroArchCfgEntry {
        var deviceName = ""
        var vendorId: Int? = null
        var productId: Int? = null
        val bindings = mutableMapOf<String, Int>()

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = LINE_REGEX.matchEntire(line) ?: continue
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            when (key) {
                "device" -> deviceName = value
                "vendor_id" -> vendorId = value.toIntOrNull()
                "product_id" -> productId = value.toIntOrNull()
                else -> if (key in RetroArchCfgEntry.SUPPORTED_BUTTON_KEYS) {
                    value.toIntOrNull()?.let { bindings[key] = it }
                }
            }
        }
        return RetroArchCfgEntry(deviceName, vendorId, productId, bindings)
    }

    fun parse(input: InputStream): RetroArchCfgEntry =
        parse(input.bufferedReader().readText())
}
