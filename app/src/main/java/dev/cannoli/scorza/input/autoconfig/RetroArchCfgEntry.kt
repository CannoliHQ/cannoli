package dev.cannoli.scorza.input.autoconfig

enum class CfgHatDirection { UP, DOWN, LEFT, RIGHT }

data class HatRef(
    val hat: Int,
    val direction: CfgHatDirection,
)

data class AxisRef(
    val axis: Int,
    val direction: Int, // +1 or -1
)

data class RetroArchCfgEntry(
    val deviceName: String,
    val vendorId: Int?,
    val productId: Int?,
    val buttonBindings: Map<String, Int>,
    val axisBindings: Map<String, AxisRef> = emptyMap(),
    val hatBindings: Map<String, HatRef> = emptyMap(),
    val displayName: String? = null,
    val buildModel: String? = null,
    val sourceMask: Int? = null,
    val confirmButton: String? = null,
    val glyphStyle: String? = null,
    val excludeFromGameplay: Boolean = false,
    val cannoliUser: Boolean = false,
    val provenance: CfgProvenance? = null,
    val builtin: Boolean? = null,
    val defaultControllerType: Int? = null,
    // Null means the key is absent, so the importer injects the platform menu defaults; an empty
    // list means the user cleared the menu. RA's menu_toggle_btn can express neither.
    val cannoliMenuKeycodes: List<Int>? = null,
    val fileName: String? = null,
    val unmodeledLines: List<String> = emptyList(),
) {
    // Provenance wins where present; cannoliUser is the permanent fallback for cfgs written before
    // the key existed. Nothing rewrites those files, so deleting this fallback would silently make
    // every one of them unowned, and the seeder would then overwrite the user's mapping.
    val isUserOwned: Boolean get() = provenance?.let { it == CfgProvenance.USER } ?: cannoliUser

    companion object {
        val SUPPORTED_BUTTON_KEYS = setOf(
            "a_btn", "b_btn", "x_btn", "y_btn",
            "l_btn", "r_btn",
            "l2_btn", "r2_btn",
            "l3_btn", "r3_btn",
            "start_btn", "select_btn",
            "up_btn", "down_btn", "left_btn", "right_btn",
            "menu_toggle_btn"
        )

        val SUPPORTED_AXIS_KEYS = setOf(
            "l2_axis", "r2_axis",
            "l_x_plus_axis", "l_x_minus_axis",
            "l_y_plus_axis", "l_y_minus_axis",
            "r_x_plus_axis", "r_x_minus_axis",
            "r_y_plus_axis", "r_y_minus_axis",
        )

        // Keys RetroArchCfgWriter regenerates from the model; every other line in a cfg is carried
        // through untouched. input_menu_toggle_btn stays managed even when the writer deliberately
        // omits it, so a cleared menu never leaves the old line behind for RetroArch to read.
        val MANAGED_KEYS: Set<String> = buildSet {
            add("input_driver")
            add("input_device")
            add("input_device_display_name")
            add("input_vendor_id")
            add("input_product_id")
            SUPPORTED_BUTTON_KEYS.mapTo(this) { "input_$it" }
            SUPPORTED_AXIS_KEYS.mapTo(this) { "input_$it" }
            addAll(
                listOf(
                    "cannoli_user",
                    "cannoli_source",
                    "cannoli_builtin",
                    "cannoli_confirm_button",
                    "cannoli_glyph_style",
                    "cannoli_exclude_from_gameplay",
                    "cannoli_default_controller_type",
                    "cannoli_descriptor",
                    "cannoli_build_model",
                    "cannoli_source_mask",
                    "cannoli_menu_keycodes",
                )
            )
        }
    }
}
