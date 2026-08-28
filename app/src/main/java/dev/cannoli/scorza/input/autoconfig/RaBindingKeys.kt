package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton

// RetroArch names the face buttons by their SNES positions, so a_btn is the right-hand button and
// b_btn the bottom one: they cross against the canonical east/south naming.
enum class RaButtonKey(val cfgKey: String, val canonical: CanonicalButton) {
    A("a_btn", CanonicalButton.BTN_EAST),
    B("b_btn", CanonicalButton.BTN_SOUTH),
    X("x_btn", CanonicalButton.BTN_NORTH),
    Y("y_btn", CanonicalButton.BTN_WEST),
    L("l_btn", CanonicalButton.BTN_L),
    R("r_btn", CanonicalButton.BTN_R),
    L2("l2_btn", CanonicalButton.BTN_L2),
    R2("r2_btn", CanonicalButton.BTN_R2),
    L3("l3_btn", CanonicalButton.BTN_L3),
    R3("r3_btn", CanonicalButton.BTN_R3),
    START("start_btn", CanonicalButton.BTN_START),
    SELECT("select_btn", CanonicalButton.BTN_SELECT),
    UP("up_btn", CanonicalButton.BTN_UP),
    DOWN("down_btn", CanonicalButton.BTN_DOWN),
    LEFT("left_btn", CanonicalButton.BTN_LEFT),
    RIGHT("right_btn", CanonicalButton.BTN_RIGHT),
    MENU("menu_toggle_btn", CanonicalButton.BTN_MENU);

    companion object {
        val CFG_KEYS: Set<String> = entries.mapTo(LinkedHashSet()) { it.cfgKey }

        private val byCfgKey = entries.associateBy { it.cfgKey }
        private val byCanonical = entries.associateBy { it.canonical }

        fun forCfgKey(cfgKey: String): RaButtonKey? = byCfgKey[cfgKey]

        fun forCanonical(canonical: CanonicalButton): RaButtonKey? = byCanonical[canonical]
    }
}

enum class RaAxisKey(
    val cfgKey: String,
    val canonical: CanonicalButton,
    val analogRole: AnalogRole,
    // Null on the trigger and d-pad keys: they name no direction and carry the sign in the value
    // instead, so one key serves a binding of either sign.
    private val positive: Boolean? = null,
) {
    L2("l2_axis", CanonicalButton.BTN_L2, AnalogRole.DIGITAL_BUTTON),
    R2("r2_axis", CanonicalButton.BTN_R2, AnalogRole.DIGITAL_BUTTON),
    UP("up_axis", CanonicalButton.BTN_UP, AnalogRole.DIGITAL_BUTTON),
    DOWN("down_axis", CanonicalButton.BTN_DOWN, AnalogRole.DIGITAL_BUTTON),
    LEFT("left_axis", CanonicalButton.BTN_LEFT, AnalogRole.DIGITAL_BUTTON),
    RIGHT("right_axis", CanonicalButton.BTN_RIGHT, AnalogRole.DIGITAL_BUTTON),
    L_X_PLUS("l_x_plus_axis", CanonicalButton.BTN_LSTICK_X, AnalogRole.ANALOG_STICK, positive = true),
    L_X_MINUS("l_x_minus_axis", CanonicalButton.BTN_LSTICK_X, AnalogRole.ANALOG_STICK, positive = false),
    L_Y_PLUS("l_y_plus_axis", CanonicalButton.BTN_LSTICK_Y, AnalogRole.ANALOG_STICK, positive = true),
    L_Y_MINUS("l_y_minus_axis", CanonicalButton.BTN_LSTICK_Y, AnalogRole.ANALOG_STICK, positive = false),
    R_X_PLUS("r_x_plus_axis", CanonicalButton.BTN_RSTICK_X, AnalogRole.ANALOG_STICK, positive = true),
    R_X_MINUS("r_x_minus_axis", CanonicalButton.BTN_RSTICK_X, AnalogRole.ANALOG_STICK, positive = false),
    R_Y_PLUS("r_y_plus_axis", CanonicalButton.BTN_RSTICK_Y, AnalogRole.ANALOG_STICK, positive = true),
    R_Y_MINUS("r_y_minus_axis", CanonicalButton.BTN_RSTICK_Y, AnalogRole.ANALOG_STICK, positive = false);

    companion object {
        val CFG_KEYS: Set<String> = entries.mapTo(LinkedHashSet()) { it.cfgKey }

        private val byCfgKey = entries.associateBy { it.cfgKey }

        fun forCfgKey(cfgKey: String): RaAxisKey? = byCfgKey[cfgKey]

        fun forBinding(canonical: CanonicalButton, role: AnalogRole, positive: Boolean): RaAxisKey? =
            entries.firstOrNull {
                it.canonical == canonical && it.analogRole == role &&
                    (it.positive == null || it.positive == positive)
            }
    }
}
