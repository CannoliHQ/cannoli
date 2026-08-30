package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.TriggerAxes
import dev.cannoli.scorza.input.legend.LegendResolver

class AndroidDefaultMappingFactory(
    private val legendResolver: LegendResolver = LegendResolver(),
    /**
     * Shown for a pad that reports no name of its own, so it reaches the controllers list and the
     * connected OSD. Passed in rather than read here: this class has no Context and the tests build
     * it bare, so the caller that has one supplies the translated string.
     */
    private val genericName: String = "Generic Controller",
) {

    private val DEFAULT_BINDINGS: Map<CanonicalButton, List<Int>> = mapOf(
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

    // A pad reporting its triggers as axes emits no keycode for them, so the defaults above leave
    // L2 and R2 unbound on it. Which axis carries a trigger is a choice the pad makes and only it
    // can answer, so the binding follows what it declares. RetroArch holds one axis per trigger,
    // so a pad declaring both conventions is taken at whichever TriggerAxes prefers.
    private fun triggerAxisBindings(declared: Set<Int>): Map<CanonicalButton, List<InputBinding>> {
        val (resting, activeMin, activeMax) =
            RetroArchAutoconfigImporter.axisRange(direction = 1, role = AnalogRole.DIGITAL_BUTTON)
        return TriggerAxes.byButton.mapNotNull { (button, candidates) ->
            val axis = candidates.firstOrNull { it in declared } ?: return@mapNotNull null
            button to listOf(
                InputBinding.Axis(
                    axis = axis,
                    restingValue = resting,
                    activeMin = activeMin,
                    activeMax = activeMax,
                    digitalThreshold = 0.5f,
                    analogRole = AnalogRole.DIGITAL_BUTTON,
                )
            )
        }.toMap()
    }

    fun create(
        device: ConnectedDevice,
    ): DeviceMapping {
        val profile = legendResolver.resolve(
            vendorId = device.vendorId,
            productId = device.productId,
        )
        val fallbackId = "${device.vendorId}_${device.productId}_${device.name.hashCode()}"
        val nameSlug = device.name.takeIf { it.isNotEmpty() }
            ?.let { RetroArchAutoconfigImporter.slugify(it) }
            ?.takeIf { it.isNotEmpty() }
        val safeId = "android_default_" + (nameSlug ?: fallbackId)
        val faceBindings = profile.faceLayout.standardFaceBindings().mapValues { (_, keyCode) ->
            listOf(InputBinding.Button(keyCode))
        }
        val triggerAxes = triggerAxisBindings(device.declaredTriggerAxes)
        return DeviceMapping(
            id = safeId,
            displayName = device.name.ifEmpty { genericName },
            match = DeviceMatchRule(
                name = device.name.ifEmpty { null },
                vendorId = device.vendorId.takeIf { it != 0 },
                productId = device.productId.takeIf { it != 0 },
                builtin = device.isBuiltIn,
            ),
            bindings = DEFAULT_BINDINGS.mapValues { (button, keyCodes) ->
                keyCodes.map { InputBinding.Button(it) } + triggerAxes[button].orEmpty()
            } + faceBindings,
            menuConfirm = profile.menuConfirm,
            menuBack = RetroArchAutoconfigImporter.oppositeOf(profile.menuConfirm),
            glyphStyle = profile.glyphStyle,
            source = MappingSource.ANDROID_DEFAULT,
        )
    }
}
