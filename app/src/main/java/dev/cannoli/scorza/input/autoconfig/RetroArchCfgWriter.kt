package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding

object RetroArchCfgWriter {

    private val INJECTED_MENU_DEFAULTS = setOf(4, 110)

    fun write(mapping: DeviceMapping, debugBuild: Boolean = false): String = buildString {
        line("input_driver", "android")
        line("input_device", mapping.match.name ?: mapping.displayName)
        line("input_device_display_name", mapping.displayName)
        mapping.match.vendorId?.let { line("input_vendor_id", it.toString()) }
        mapping.match.productId?.let { line("input_product_id", it.toString()) }

        for ((canonical, bindings) in mapping.bindings) {
            val btnKey = RaButtonKey.forCanonical(canonical)?.cfgKey
            val effective = if (canonical == CanonicalButton.BTN_MENU) {
                bindings.filterIsInstance<InputBinding.Button>()
                    .filterNot { it.keyCode in INJECTED_MENU_DEFAULTS }
            } else {
                bindings
            }
            var digitalWritten = false
            val axisKeysWritten = mutableSetOf<String>()
            for (binding in effective) {
                when (binding) {
                    is InputBinding.Button -> if (btnKey != null && !digitalWritten) {
                        val value = if (canonical == CanonicalButton.BTN_MENU && !debugBuild) {
                            "nul"
                        } else {
                            binding.keyCode.toString()
                        }
                        line("input_$btnKey", value)
                        digitalWritten = true
                    }
                    is InputBinding.Hat -> if (btnKey != null && !digitalWritten) {
                        line("input_$btnKey", "h0" + binding.direction.name.lowercase())
                        digitalWritten = true
                    }
                    is InputBinding.Axis -> {}
                }
            }
            for (binding in effective) {
                if (binding !is InputBinding.Axis) continue
                val positive = binding.activeMax >= 0f
                val key = RaAxisKey.forBinding(canonical, binding.analogRole, positive)?.cfgKey ?: continue
                val slot = RaAxisSlots.toRaSlot(binding.axis) ?: continue
                if (axisKeysWritten.add(key)) {
                    val sign = if (positive) "+" else "-"
                    line("input_$key", "$sign$slot")
                }
            }
        }

        line("cannoli_source", if (mapping.userEdited) "USER" else "INPUT_DB")
        mapping.match.builtin?.let { line("cannoli_builtin", it.toString()) }
        line("cannoli_confirm_button", mapping.menuConfirm.name)
        line("cannoli_glyph_style", mapping.glyphStyle.name)
        line("cannoli_exclude_from_gameplay", mapping.excludeFromGameplay.toString())
        mapping.defaultControllerTypeId?.let { line("cannoli_default_controller_type", it.toString()) }
        mapping.match.androidBuildModel?.let { line("cannoli_build_model", it) }
        mapping.match.aliases.takeIf { it.isNotEmpty() }
            ?.let { line(DeviceAliases.KEY, DeviceAliases.format(it)) }
        mapping.match.sourceMask?.let { line("cannoli_source_mask", it.toString()) }
        if (mapping.userEdited) {
            line(
                "cannoli_menu_keycodes",
                mapping.bindings[CanonicalButton.BTN_MENU].orEmpty()
                    .filterIsInstance<InputBinding.Button>()
                    .joinToString(",") { it.keyCode.toString() },
            )
        }

        for (line in mapping.unmodeledLines) appendLine(line)
    }

    private fun StringBuilder.line(key: String, value: String?) {
        if (value != null) appendLine("$key = \"${value.replace("\"", "")}\"")
    }
}