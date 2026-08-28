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
    val deviceAliases: List<String> = emptyList(),
    val fileName: String? = null,
    val unmodeledLines: List<String> = emptyList(),
) {
    // Provenance wins where present; cannoliUser is the permanent fallback for cfgs written before
    // the key existed. Nothing rewrites those files, so deleting this fallback would silently make
    // every one of them unowned, and the seeder would then overwrite the user's mapping.
    val isUserOwned: Boolean get() = provenance?.let { it == CfgProvenance.USER } ?: cannoliUser

    companion object {
        // Keys RetroArchCfgWriter regenerates from the model; every other line in a cfg is carried
        // through untouched. input_menu_toggle_btn stays managed even when the writer deliberately
        // omits it, so a cleared menu never leaves the old line behind for RetroArch to read.
        val MANAGED_KEYS: Set<String> = buildSet {
            add("input_driver")
            add("input_device")
            add("input_device_display_name")
            add("input_vendor_id")
            add("input_product_id")
            RaButtonKey.CFG_KEYS.mapTo(this) { "input_$it" }
            RaAxisKey.CFG_KEYS.mapTo(this) { "input_$it" }
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
                    DeviceAliases.KEY,
                )
            )
        }
    }
}
