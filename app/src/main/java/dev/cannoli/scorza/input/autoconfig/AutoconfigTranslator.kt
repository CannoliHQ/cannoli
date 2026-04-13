package dev.cannoli.scorza.input.autoconfig

object AutoconfigTranslator {

    private val RA_TO_CANNOLI = mapOf(
        "b_btn" to "btn_south",
        "a_btn" to "btn_east",
        "y_btn" to "btn_west",
        "x_btn" to "btn_north",
        "l_btn" to "btn_l",
        "r_btn" to "btn_r",
        "l2_btn" to "btn_l2",
        "r2_btn" to "btn_r2",
        "l3_btn" to "btn_l3",
        "r3_btn" to "btn_r3",
        "start_btn" to "btn_start",
        "select_btn" to "btn_select",
        "up_btn" to "btn_up",
        "down_btn" to "btn_down",
        "left_btn" to "btn_left",
        "right_btn" to "btn_right",
        "menu_toggle_btn" to "btn_menu"
    )

    fun toProfile(entry: RetroArchCfgEntry): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for ((raKey, keyCode) in entry.buttonBindings) {
            val cannoliKey = RA_TO_CANNOLI[raKey] ?: continue
            out[cannoliKey] = keyCode
        }
        return out
    }
}
