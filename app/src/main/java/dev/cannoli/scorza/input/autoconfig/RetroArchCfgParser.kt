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
        var buildModel: String? = null
        var sourceMask: Int? = null
        var confirmButton: String? = null
        var glyphStyle: String? = null
        var excludeFromGameplay = false
        var cannoliUser = false
        var provenance: CfgProvenance? = null
        var builtin: Boolean? = null
        var defaultControllerType: Int? = null
        var cannoliMenuKeycodes: List<Int>? = null
        var deviceAliases: List<String> = emptyList()
        val bindings = mutableMapOf<String, Int>()
        val axes = mutableMapOf<String, AxisRef>()
        val hats = mutableMapOf<String, HatRef>()
        val unmodeledLines = mutableListOf<String>()

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = LINE_REGEX.matchEntire(line)
            val fullKey = if (match != null) {
                "${match.groupValues[1]}_${match.groupValues[2]}"
            } else {
                line.substringBefore('=').trim()
            }
            if (fullKey !in RetroArchCfgEntry.MANAGED_KEYS) {
                unmodeledLines.add(line)
                continue
            }
            if (match == null) continue
            val prefix = match.groupValues[1]
            val key = match.groupValues[2]
            val value = match.groupValues[3]
            when {
                prefix == "input" && key == "device" -> deviceName = value
                prefix == "input" && key == "device_display_name" -> displayName = value
                prefix == "input" && key == "vendor_id" -> vendorId = value.toIntOrNull()
                prefix == "input" && key == "product_id" -> productId = value.toIntOrNull()
                prefix == "input" && key in RaButtonKey.CFG_KEYS -> {
                    val hatMatch = HAT_VALUE_REGEX.matchEntire(value)
                    if (hatMatch != null) {
                        val hat = hatMatch.groupValues[1].toIntOrNull() ?: continue
                        val direction = when (hatMatch.groupValues[2]) {
                            "up" -> CfgHatDirection.UP
                            "down" -> CfgHatDirection.DOWN
                            "left" -> CfgHatDirection.LEFT
                            "right" -> CfgHatDirection.RIGHT
                            else -> continue
                        }
                        hats[key] = HatRef(hat, direction)
                        continue
                    }
                    val axisMatch = AXIS_VALUE_REGEX.matchEntire(value)
                    if (axisMatch != null) {
                        val sign = if (axisMatch.groupValues[1] == "+") 1 else -1
                        val axis = axisMatch.groupValues[2].toIntOrNull() ?: continue
                        axes[key.removeSuffix("_btn") + "_axis"] = AxisRef(axis, sign)
                        continue
                    }
                    val asInt = value.toIntOrNull() ?: continue
                    bindings[key] = asInt
                }
                prefix == "input" && key in RaAxisKey.CFG_KEYS -> {
                    val m = AXIS_VALUE_REGEX.matchEntire(value) ?: continue
                    val sign = if (m.groupValues[1] == "+") 1 else -1
                    val axis = m.groupValues[2].toIntOrNull() ?: continue
                    axes[key] = AxisRef(axis, sign)
                }
                prefix == "cannoli" && key == "user" -> cannoliUser = value.toBoolean()
                prefix == "cannoli" && key == "source" -> provenance = CfgProvenance.parse(value)
                prefix == "cannoli" && key == "builtin" -> builtin = value.toBooleanStrictOrNull()
                prefix == "cannoli" && key == "confirm_button" -> confirmButton = value
                prefix == "cannoli" && key == "glyph_style" -> glyphStyle = value
                prefix == "cannoli" && key == "exclude_from_gameplay" -> excludeFromGameplay = value.toBoolean()
                prefix == "cannoli" && key == "build_model" -> buildModel = value
                prefix == "cannoli" && key == "source_mask" -> sourceMask = value.toIntOrNull()
                prefix == "cannoli" && key == "default_controller_type" -> defaultControllerType = value.toIntOrNull()
                prefix == "cannoli" && key == "device_aliases" -> deviceAliases = DeviceAliases.parse(value)
                prefix == "cannoli" && key == "menu_keycodes" -> cannoliMenuKeycodes =
                    if (value.isEmpty()) emptyList() else value.split(",").mapNotNull { it.trim().toIntOrNull() }
            }
        }
        return RetroArchCfgEntry(
            deviceName, vendorId, productId, bindings, axes, hats,
            displayName, buildModel, sourceMask, confirmButton, glyphStyle,
            excludeFromGameplay, cannoliUser, provenance, builtin, defaultControllerType, cannoliMenuKeycodes,
            deviceAliases, fileName,
            unmodeledLines
        )
    }

    fun parse(input: InputStream, fileName: String? = null): RetroArchCfgEntry =
        parse(input.bufferedReader().readText(), fileName)
}
