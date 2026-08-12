package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding

object RetroArchCfgWriter {

    private val CANONICAL_TO_BTN: Map<CanonicalButton, String> = mapOf(
        CanonicalButton.BTN_SOUTH to "b_btn",
        CanonicalButton.BTN_EAST to "a_btn",
        CanonicalButton.BTN_WEST to "y_btn",
        CanonicalButton.BTN_NORTH to "x_btn",
        CanonicalButton.BTN_L to "l_btn",
        CanonicalButton.BTN_L2 to "l2_btn",
        CanonicalButton.BTN_R to "r_btn",
        CanonicalButton.BTN_R2 to "r2_btn",
        CanonicalButton.BTN_L3 to "l3_btn",
        CanonicalButton.BTN_R3 to "r3_btn",
        CanonicalButton.BTN_START to "start_btn",
        CanonicalButton.BTN_SELECT to "select_btn",
        CanonicalButton.BTN_UP to "up_btn",
        CanonicalButton.BTN_DOWN to "down_btn",
        CanonicalButton.BTN_LEFT to "left_btn",
        CanonicalButton.BTN_RIGHT to "right_btn",
        CanonicalButton.BTN_MENU to "menu_toggle_btn",
    )

    // 4 (BACK) and 110 (BUTTON_MODE) are the platform defaults the importer injects into
    // BTN_MENU; they describe the platform, not this pad, so they never serialize.
    private val INJECTED_MENU_DEFAULTS = setOf(4, 110)

    fun write(mapping: DeviceMapping, debugBuild: Boolean = false): String = buildString {
        line("input_driver", "android")
        line("input_device", mapping.match.name ?: mapping.displayName)
        line("input_device_display_name", mapping.displayName)
        mapping.match.vendorId?.let { line("input_vendor_id", it.toString()) }
        mapping.match.productId?.let { line("input_product_id", it.toString()) }

        for ((canonical, bindings) in mapping.bindings) {
            // The stick canonicals (BTN_LSTICK_X etc.) carry only axis bindings and have no RA
            // digital button key of their own -- l3_btn/r3_btn cover the stick click separately
            // -- so btnKey stays null for them and only the Axis branch below applies.
            val btnKey = CANONICAL_TO_BTN[canonical]
            // RetroArch reads this file too, and it opens its own menu on whatever
            // input_menu_toggle_btn names. A hat or axis there would fire in-game because motion
            // events never reach the launcher's keycode intercept, so the menu takes buttons only.
            val effective = if (canonical == CanonicalButton.BTN_MENU) {
                bindings.filterIsInstance<InputBinding.Button>()
                    .filterNot { it.keyCode in INJECTED_MENU_DEFAULTS }
            } else {
                bindings
            }
            // RA holds one value per key, so only the first button or hat claims
            // input_<btnKey>. Iteration continues past it because a canonical can carry axis
            // bindings too (l3_btn alongside the l_x/l_y stick keys), and each axis writes its
            // own separate key.
            var digitalWritten = false
            val axisKeysWritten = mutableSetOf<String>()
            for (binding in effective) {
                when (binding) {
                    is InputBinding.Button -> if (btnKey != null && !digitalWritten) {
                        // Release builds close RA's own menu off entirely: a real bound key still
                        // exists (the Cannoli IGM trigger reads it independently, see
                        // resolveMenuKeycodes), but RA itself is told the key is unbound.
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
                    is InputBinding.Axis -> {
                        val key = axisKeyFor(canonical, binding)
                        // A pad reporting the same logical trigger on two axes (LTRIGGER and
                        // BRAKE, say) captures two bindings on one canonical. RA holds one value
                        // per key, so only the first binding that resolves to a given key claims
                        // it; a stick's plus and minus bindings resolve to distinct keys and both
                        // still write.
                        if (key != null && axisKeysWritten.add(key)) {
                            val sign = if (binding.activeMax >= 0f) "+" else "-"
                            line("input_$key", "$sign${binding.axis}")
                        }
                    }
                }
            }
        }

        line("cannoli_user", mapping.userEdited.toString())
        line("cannoli_confirm_button", mapping.menuConfirm.name)
        line("cannoli_glyph_style", mapping.glyphStyle.name)
        line("cannoli_exclude_from_gameplay", mapping.excludeFromGameplay.toString())
        mapping.defaultControllerTypeId?.let { line("cannoli_default_controller_type", it.toString()) }
        mapping.match.descriptor?.let { line("cannoli_descriptor", it) }
        mapping.match.androidBuildModel?.let { line("cannoli_build_model", it) }
        mapping.match.sourceMask?.let { line("cannoli_source_mask", it.toString()) }
        // input_menu_toggle_btn is lossy by design (one keycode, defaults stripped), so a user's
        // menu edit is kept verbatim here or the importer would inject the defaults back over it.
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

    // A quote inside a value would produce a line the parser cannot match, so the edit would
    // silently revert on the next read and RetroArch would see a malformed key.
    private fun StringBuilder.line(key: String, value: String?) {
        if (value != null) appendLine("$key = \"${value.replace("\"", "")}\"")
    }

    private fun axisKeyFor(canonical: CanonicalButton, axis: InputBinding.Axis): String? {
        if (axis.analogRole == AnalogRole.DIGITAL_BUTTON) {
            return when (canonical) {
                CanonicalButton.BTN_L2 -> "l2_axis"
                CanonicalButton.BTN_R2 -> "r2_axis"
                else -> null
            }
        }
        val prefix = when (canonical) {
            CanonicalButton.BTN_LSTICK_X -> "l_x"
            CanonicalButton.BTN_LSTICK_Y -> "l_y"
            CanonicalButton.BTN_RSTICK_X -> "r_x"
            CanonicalButton.BTN_RSTICK_Y -> "r_y"
            else -> return null
        }
        val direction = if (axis.activeMax >= 0f) "plus" else "minus"
        return "${prefix}_${direction}_axis"
    }
}