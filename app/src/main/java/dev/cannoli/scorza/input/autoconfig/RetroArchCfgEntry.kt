package dev.cannoli.scorza.input.autoconfig

data class RetroArchCfgEntry(
    val deviceName: String,
    val vendorId: Int?,
    val productId: Int?,
    val buttonBindings: Map<String, Int>
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
    }
}
