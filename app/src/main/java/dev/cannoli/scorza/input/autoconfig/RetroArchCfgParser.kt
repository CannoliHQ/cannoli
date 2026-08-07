package dev.cannoli.scorza.input.autoconfig

import java.io.InputStream

object RetroArchCfgParser {

    private val LINE_REGEX = Regex("""^\s*(input|cannoli)_([a-z0-9_]+)\s*=\s*"([^"]*)"\s*$""")
    private val AXIS_VALUE_REGEX = Regex("""^([+-])([0-9]+)$""")
    private val HAT_VALUE_REGEX = Regex("""^h(\d+)(up|down|left|right)$""")

    fun parse(source: String, fileName: String? = null): RetroArchCfgEntry {
        var deviceName = ""
        var vendorId: Int? = null
        var productId: Int? = null
        var displayName: String? = null
        var descriptor: String? = null
        var buildModel: String? = null
        var sourceMask: Int? = null
        var confirmButton: String? = null
        var glyphStyle: String? = null
        var excludeFromGameplay = false
        var cannoliUser = false
        var defaultControllerType: Int? = null
        val bindings = mutableMapOf<String, Int>()
        val axes = mutableMapOf<String, AxisRef>()
        val hats = mutableMapOf<String, HatRef>()

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = LINE_REGEX.matchEntire(line) ?: continue
            val prefix = match.groupValues[1]
            val key = match.groupValues[2]
            val value = match.groupValues[3]
            when {
                prefix == "input" && key == "device" -> deviceName = value
                prefix == "input" && key == "device_display_name" -> displayName = value
                prefix == "input" && key == "vendor_id" -> vendorId = value.toIntOrNull()
                prefix == "input" && key == "product_id" -> productId = value.toIntOrNull()
                prefix == "input" && key in RetroArchCfgEntry.SUPPORTED_BUTTON_KEYS -> {
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
                prefix == "input" && key in RetroArchCfgEntry.SUPPORTED_AXIS_KEYS -> {
                    val m = AXIS_VALUE_REGEX.matchEntire(value) ?: continue
                    val sign = if (m.groupValues[1] == "+") 1 else -1
                    val axis = m.groupValues[2].toIntOrNull() ?: continue
                    axes[key] = AxisRef(axis, sign)
                }
                prefix == "cannoli" && key == "user" -> cannoliUser = value.toBoolean()
                prefix == "cannoli" && key == "confirm_button" -> confirmButton = value
                prefix == "cannoli" && key == "glyph_style" -> glyphStyle = value
                prefix == "cannoli" && key == "exclude_from_gameplay" -> excludeFromGameplay = value.toBoolean()
                prefix == "cannoli" && key == "descriptor" -> descriptor = value
                prefix == "cannoli" && key == "build_model" -> buildModel = value
                prefix == "cannoli" && key == "source_mask" -> sourceMask = value.toIntOrNull()
                prefix == "cannoli" && key == "default_controller_type" -> defaultControllerType = value.toIntOrNull()
            }
        }
        return RetroArchCfgEntry(
            deviceName, vendorId, productId, bindings, axes, hats,
            displayName, descriptor, buildModel, sourceMask, confirmButton, glyphStyle,
            excludeFromGameplay, cannoliUser, defaultControllerType, fileName
        )
    }

    fun parse(input: InputStream, fileName: String? = null): RetroArchCfgEntry =
        parse(input.bufferedReader().readText(), fileName)
}
