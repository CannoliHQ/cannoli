package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMatchRule
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.InputBinding
import dev.cannoli.scorza.input.v2.TemplateSource

object AndroidDefaultTemplateFactory {

    private val DEFAULT_BINDINGS: Map<CanonicalButton, Int> = mapOf(
        CanonicalButton.BTN_SOUTH to 96,
        CanonicalButton.BTN_EAST to 97,
        CanonicalButton.BTN_WEST to 99,
        CanonicalButton.BTN_NORTH to 100,
        CanonicalButton.BTN_L to 102,
        CanonicalButton.BTN_R to 103,
        CanonicalButton.BTN_L2 to 104,
        CanonicalButton.BTN_R2 to 105,
        CanonicalButton.BTN_L3 to 106,
        CanonicalButton.BTN_R3 to 107,
        CanonicalButton.BTN_START to 108,
        CanonicalButton.BTN_SELECT to 109,
        CanonicalButton.BTN_UP to 19,
        CanonicalButton.BTN_DOWN to 20,
        CanonicalButton.BTN_LEFT to 21,
        CanonicalButton.BTN_RIGHT to 22,
    )

    fun create(device: ConnectedDevice): DeviceTemplate {
        val safeId = "android_default_" + device.descriptor.ifEmpty {
            "${device.vendorId}_${device.productId}_${device.name.hashCode()}"
        }
        return DeviceTemplate(
            id = safeId,
            displayName = device.name.ifEmpty { "Generic Controller" },
            match = DeviceMatchRule(
                name = device.name.ifEmpty { null },
                vendorId = device.vendorId.takeIf { it != 0 },
                productId = device.productId.takeIf { it != 0 },
            ),
            bindings = DEFAULT_BINDINGS.mapValues { (_, keyCode) ->
                listOf(InputBinding.Button(keyCode))
            },
            source = TemplateSource.ANDROID_DEFAULT,
        )
    }
}
