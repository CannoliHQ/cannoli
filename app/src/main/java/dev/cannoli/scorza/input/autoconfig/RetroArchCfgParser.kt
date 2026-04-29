package dev.cannoli.scorza.input.autoconfig

import java.io.InputStream

object RetroArchCfgParser {

    private val LINE_REGEX = Regex("""^\s*input_([a-z0-9_]+)\s*=\s*"([^"]*)"\s*$""")
    private val AXIS_VALUE_REGEX = Regex("""^([+-])([0-9]+)$""")
    private val HAT_VALUE_REGEX = Regex("""^h(\d+)(up|down|left|right)$""")

    fun parse(source: String): RetroArchCfgEntry {
        var deviceName = ""
        var vendorId: Int? = null
        var productId: Int? = null
        val bindings = mutableMapOf<String, Int>()
        val axes = mutableMapOf<String, AxisRef>()
        val hats = mutableMapOf<String, HatRef>()

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = LINE_REGEX.matchEntire(line) ?: continue
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            when {
                key == "device" -> deviceName = value
                key == "vendor_id" -> vendorId = value.toIntOrNull()
                key == "product_id" -> productId = value.toIntOrNull()
                key in RetroArchCfgEntry.SUPPORTED_BUTTON_KEYS -> {
                    val asInt = value.toIntOrNull()
                    if (asInt != null) {
                        bindings[key] = asInt
                        continue
                    }
                    val hatMatch = HAT_VALUE_REGEX.matchEntire(value) ?: continue
                    val hat = hatMatch.groupValues[1].toIntOrNull() ?: continue
                    val direction = when (hatMatch.groupValues[2]) {
                        "up" -> CfgHatDirection.UP
                        "down" -> CfgHatDirection.DOWN
                        "left" -> CfgHatDirection.LEFT
                        "right" -> CfgHatDirection.RIGHT
                        else -> continue
                    }
                    hats[key] = HatRef(hat, direction)
                }
                key in RetroArchCfgEntry.SUPPORTED_AXIS_KEYS -> {
                    val m = AXIS_VALUE_REGEX.matchEntire(value) ?: continue
                    val sign = if (m.groupValues[1] == "+") 1 else -1
                    val axis = m.groupValues[2].toIntOrNull() ?: continue
                    axes[key] = AxisRef(axis, sign)
                }
            }
        }
        return RetroArchCfgEntry(deviceName, vendorId, productId, bindings, axes, hats)
    }

    fun parse(input: InputStream): RetroArchCfgEntry =
        parse(input.bufferedReader().readText())
}
