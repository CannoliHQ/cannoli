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
    val descriptor: String? = null,
    val buildModel: String? = null,
    val sourceMask: Int? = null,
    val confirmButton: String? = null,
    val glyphStyle: String? = null,
    val excludeFromGameplay: Boolean = false,
    val cannoliUser: Boolean = false,
    val defaultControllerType: Int? = null,
    val fileName: String? = null,
) {
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
    }
}
