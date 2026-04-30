package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.MappingSource
import dev.cannoli.scorza.input.v2.hints.ControllerHintTable

object AndroidDefaultMappingFactory {

    private val DEFAULT_BINDINGS: Map<CanonicalButton, List<Int>> = mapOf(
        CanonicalButton.BTN_SOUTH to listOf(96),
        CanonicalButton.BTN_EAST to listOf(97),
        CanonicalButton.BTN_WEST to listOf(99),
        CanonicalButton.BTN_NORTH to listOf(100),
        CanonicalButton.BTN_L to listOf(102),
        CanonicalButton.BTN_R to listOf(103),
        CanonicalButton.BTN_L2 to listOf(104),
        CanonicalButton.BTN_R2 to listOf(105),
        CanonicalButton.BTN_L3 to listOf(106),
        CanonicalButton.BTN_R3 to listOf(107),
        CanonicalButton.BTN_START to listOf(108),
        CanonicalButton.BTN_SELECT to listOf(109),
        CanonicalButton.BTN_UP to listOf(19),
        CanonicalButton.BTN_DOWN to listOf(20),
        CanonicalButton.BTN_LEFT to listOf(21),
        CanonicalButton.BTN_RIGHT to listOf(22),
        // KEYCODE_BACK (4) and KEYCODE_BUTTON_MODE (110) -> open menu by default.
        CanonicalButton.BTN_MENU to listOf(4, 110),
    )

    fun create(device: ConnectedDevice, hints: ControllerHintTable): DeviceMapping {
        val hint = hints.lookup(
            vendorId = device.vendorId,
            productId = device.productId,
            buildModel = device.androidBuildModel,
        )
        val safeId = "android_default_" + device.descriptor.ifEmpty {
            "${device.vendorId}_${device.productId}_${device.name.hashCode()}"
        }
        return DeviceMapping(
            id = safeId,
            displayName = device.name.ifEmpty { "Generic Controller" },
            match = DeviceMatchRule(
                name = device.name.ifEmpty { null },
                vendorId = device.vendorId.takeIf { it != 0 },
                productId = device.productId.takeIf { it != 0 },
            ),
            bindings = DEFAULT_BINDINGS.mapValues { (_, keyCodes) ->
                keyCodes.map { InputBinding.Button(it) }
            },
            menuConfirm = hint.menuConfirm,
            menuBack = if (hint.menuConfirm == CanonicalButton.BTN_EAST) CanonicalButton.BTN_SOUTH else CanonicalButton.BTN_EAST,
            glyphStyle = hint.glyphStyle,
            source = MappingSource.ANDROID_DEFAULT,
        )
    }
}
