package dev.cannoli.scorza.input.autoconfig

enum class CfgHatDirection { UP, DOWN, LEFT, RIGHT }

data class HatRef(
    val hat: Int,
    val direction: CfgHatDirection,
)

data class AxisRef(
    val axis: Int,
    val direction: Int,
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
    val cannoliMenuKeycodes: List<Int>? = null,
    val deviceAliases: List<String> = emptyList(),
    val fileName: String? = null,
    val unmodeledLines: List<String> = emptyList(),
) {
    val isUserOwned: Boolean get() = provenance?.let { it == CfgProvenance.USER } ?: cannoliUser

    companion object {
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
