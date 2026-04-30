package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.autoconfig.AxisRef
import dev.cannoli.scorza.input.autoconfig.CfgHatDirection
import dev.cannoli.scorza.input.autoconfig.HatRef
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import dev.cannoli.scorza.input.v2.hints.ControllerHintTable

object RetroArchAutoconfigImporter {

    private val BTN_TO_CANONICAL: Map<String, CanonicalButton> = mapOf(
        "b_btn" to CanonicalButton.BTN_SOUTH,
        "a_btn" to CanonicalButton.BTN_EAST,
        "y_btn" to CanonicalButton.BTN_WEST,
        "x_btn" to CanonicalButton.BTN_NORTH,
        "l_btn" to CanonicalButton.BTN_L,
        "r_btn" to CanonicalButton.BTN_R,
        "l2_btn" to CanonicalButton.BTN_L2,
        "r2_btn" to CanonicalButton.BTN_R2,
        "l3_btn" to CanonicalButton.BTN_L3,
        "r3_btn" to CanonicalButton.BTN_R3,
        "start_btn" to CanonicalButton.BTN_START,
        "select_btn" to CanonicalButton.BTN_SELECT,
        "up_btn" to CanonicalButton.BTN_UP,
        "down_btn" to CanonicalButton.BTN_DOWN,
        "left_btn" to CanonicalButton.BTN_LEFT,
        "right_btn" to CanonicalButton.BTN_RIGHT,
        "menu_toggle_btn" to CanonicalButton.BTN_MENU,
    )

    fun import(
        entry: RetroArchCfgEntry,
        device: ConnectedDevice,
        hints: ControllerHintTable,
    ): DeviceMapping {
        val bindings = mutableMapOf<CanonicalButton, MutableList<InputBinding>>()

        for ((raKey, keyCode) in entry.buttonBindings) {
            val canonical = BTN_TO_CANONICAL[raKey] ?: continue
            bindings.getOrPut(canonical) { mutableListOf() }
                .add(InputBinding.Button(keyCode))
        }

        for ((axisKey, ref) in entry.axisBindings) {
            val (canonical, role) = mapAxisKeyToCanonicalAndRole(axisKey) ?: continue
            val (resting, activeMin, activeMax) = axisRange(ref.direction)
            bindings.getOrPut(canonical) { mutableListOf() }
                .add(
                    InputBinding.Axis(
                        axis = ref.axis,
                        restingValue = resting,
                        activeMin = activeMin,
                        activeMax = activeMax,
                        digitalThreshold = 0.5f,
                        invert = false,
                        analogRole = role,
                    )
                )
        }

        for ((btnKey, hatRef) in entry.hatBindings) {
            val canonical = BTN_TO_CANONICAL[btnKey] ?: continue
            val (axis, direction) = mapHatRefToAxisAndDirection(hatRef) ?: continue
            bindings.getOrPut(canonical) { mutableListOf() }
                .add(
                    InputBinding.Hat(
                        axis = axis,
                        direction = direction,
                        threshold = 0.5f,
                    )
                )
        }

        // Seed BTN_MENU with KEYCODE_BACK (4) + KEYCODE_BUTTON_MODE (110) if cfg didn't supply one.
        val menuBindings = bindings.getOrPut(CanonicalButton.BTN_MENU) { mutableListOf() }
        for (defaultKey in listOf(4, 110)) {
            if (menuBindings.none { it is InputBinding.Button && it.keyCode == defaultKey }) {
                menuBindings.add(InputBinding.Button(defaultKey))
            }
        }

        val safeId = stableIdFor(device, entry)
        val hint = hints.lookup(
            vendorId = device.vendorId,
            productId = device.productId,
            buildModel = device.androidBuildModel,
        )
        return DeviceMapping(
            id = safeId,
            displayName = entry.deviceName.ifEmpty { device.name.ifEmpty { "Controller" } },
            match = DeviceMatchRule(
                name = entry.deviceName.ifEmpty { device.name.ifEmpty { null } },
                vendorId = entry.vendorId ?: device.vendorId.takeIf { it != 0 },
                productId = entry.productId ?: device.productId.takeIf { it != 0 },
            ),
            bindings = bindings,
            menuConfirm = hint.menuConfirm,
            menuBack = oppositeOf(hint.menuConfirm),
            glyphStyle = hint.glyphStyle,
            source = MappingSource.RETROARCH_AUTOCONFIG,
        )
    }

    private fun oppositeOf(button: CanonicalButton): CanonicalButton = when (button) {
        CanonicalButton.BTN_EAST -> CanonicalButton.BTN_SOUTH
        CanonicalButton.BTN_SOUTH -> CanonicalButton.BTN_EAST
        else -> CanonicalButton.BTN_SOUTH
    }

    private fun mapHatRefToAxisAndDirection(ref: HatRef): Pair<Int, HatDirection>? {
        if (ref.hat != 0) return null
        return when (ref.direction) {
            CfgHatDirection.UP -> ANDROID_AXIS_HAT_Y to HatDirection.UP
            CfgHatDirection.DOWN -> ANDROID_AXIS_HAT_Y to HatDirection.DOWN
            CfgHatDirection.LEFT -> ANDROID_AXIS_HAT_X to HatDirection.LEFT
            CfgHatDirection.RIGHT -> ANDROID_AXIS_HAT_X to HatDirection.RIGHT
        }
    }

    private const val ANDROID_AXIS_HAT_X: Int = 15
    private const val ANDROID_AXIS_HAT_Y: Int = 16

    private fun mapAxisKeyToCanonicalAndRole(key: String): Pair<CanonicalButton, AnalogRole>? = when (key) {
        "l2_axis" -> CanonicalButton.BTN_L2 to AnalogRole.DIGITAL_BUTTON
        "r2_axis" -> CanonicalButton.BTN_R2 to AnalogRole.DIGITAL_BUTTON
        "l_x_plus_axis", "l_x_minus_axis" -> CanonicalButton.BTN_L3 to AnalogRole.LEFT_STICK_X
        "l_y_plus_axis", "l_y_minus_axis" -> CanonicalButton.BTN_L3 to AnalogRole.LEFT_STICK_Y
        "r_x_plus_axis", "r_x_minus_axis" -> CanonicalButton.BTN_R3 to AnalogRole.RIGHT_STICK_X
        "r_y_plus_axis", "r_y_minus_axis" -> CanonicalButton.BTN_R3 to AnalogRole.RIGHT_STICK_Y
        else -> null
    }

    private fun axisRange(direction: Int): Triple<Float, Float, Float> =
        if (direction >= 0) Triple(-1f, 0f, 1f) else Triple(1f, 0f, -1f)

    private fun stableIdFor(device: ConnectedDevice, entry: RetroArchCfgEntry): String {
        val base = entry.deviceName.ifEmpty { device.name.ifEmpty { "controller" } }
        return "ra_" + base.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }
}
