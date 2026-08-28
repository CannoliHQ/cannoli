package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.CfgHatDirection
import dev.cannoli.scorza.input.autoconfig.HatRef
import dev.cannoli.scorza.input.autoconfig.RaAxisKey
import dev.cannoli.scorza.input.autoconfig.RaAxisSlots
import dev.cannoli.scorza.input.autoconfig.RaButtonKey
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.legend.LegendResolver

object RetroArchAutoconfigImporter {

    private val legendResolver = LegendResolver()

    fun import(
        entry: RetroArchCfgEntry,
        device: ConnectedDevice,
    ): DeviceMapping {
        val bindings = mutableMapOf<CanonicalButton, MutableList<InputBinding>>()

        for ((raKey, keyCode) in entry.buttonBindings) {
            val canonical = RaButtonKey.forCfgKey(raKey)?.canonical ?: continue
            bindings.getOrPut(canonical) { mutableListOf() }
                .add(InputBinding.Button(keyCode))
        }

        for ((axisKey, ref) in entry.axisBindings) {
            val raAxis = RaAxisKey.forCfgKey(axisKey) ?: continue
            val role = raAxis.analogRole
            val (resting, activeMin, activeMax) = axisRange(ref.direction, role)
            bindings.getOrPut(raAxis.canonical) { mutableListOf() }
                .add(
                    InputBinding.Axis(
                        axis = RaAxisSlots.toAndroidAxis(ref.axis),
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
            val canonical = RaButtonKey.forCfgKey(btnKey)?.canonical ?: continue
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

        // A cfg the user edited states its menu exactly, including an empty one. Otherwise seed
        // BTN_MENU with KEYCODE_BACK (4) + KEYCODE_BUTTON_MODE (110) on top of whatever the cfg has.
        val menuKeycodes = entry.cannoliMenuKeycodes
        if (menuKeycodes != null) {
            bindings[CanonicalButton.BTN_MENU] =
                menuKeycodes.mapTo(mutableListOf<InputBinding>()) { InputBinding.Button(it) }
        } else {
            val menuBindings = bindings.getOrPut(CanonicalButton.BTN_MENU) { mutableListOf() }
            for (defaultKey in listOf(4, 110)) {
                if (menuBindings.none { it is InputBinding.Button && it.keyCode == defaultKey }) {
                    menuBindings.add(InputBinding.Button(defaultKey))
                }
            }
        }

        val safeId = stableIdFor(device, entry)
        // Phantom-rewrite handling: on hosts that rewrite a paired BT pad's VID/PID to the
        // built-in's values (Retroid family), the device's reported vid/pid don't match the
        // pad's real brand. The matched cfg has the right brand vid/pid in its header, so try
        // the cfg's vid/pid first; only fall through to the device's reported vid/pid when the
        // cfg doesn't carry one.
        val profile = legendResolver.resolve(
            vendorId = entry.vendorId ?: device.vendorId,
            productId = entry.productId ?: device.productId,
        )
        val confirm = entry.confirmButton
            ?.let { runCatching { CanonicalButton.valueOf(it) }.getOrNull() }
            ?: profile.menuConfirm
        val glyph = entry.glyphStyle
            ?.let { runCatching { GlyphStyle.valueOf(it) }.getOrNull() }
            ?: profile.glyphStyle
        return DeviceMapping(
            id = entry.fileName?.removeSuffix(".cfg") ?: safeId,
            displayName = entry.displayName
                ?: device.name.ifEmpty { entry.deviceName.ifEmpty { "Controller" } },
            match = DeviceMatchRule(
                name = entry.deviceName.ifEmpty { device.name.ifEmpty { null } },
                vendorId = entry.vendorId ?: device.vendorId.takeIf { it != 0 },
                productId = entry.productId ?: device.productId.takeIf { it != 0 },
                androidBuildModel = entry.buildModel,
                builtin = entry.builtin,
                aliases = entry.deviceAliases,
            ),
            bindings = bindings,
            menuConfirm = confirm,
            menuBack = oppositeOf(confirm),
            glyphStyle = glyph,
            excludeFromGameplay = entry.excludeFromGameplay,
            defaultControllerTypeId = entry.defaultControllerType,
            source = if (entry.isUserOwned) MappingSource.USER_WIZARD else MappingSource.RETROARCH_AUTOCONFIG,
            userEdited = entry.isUserOwned,
            unmodeledLines = entry.unmodeledLines,
        )
    }

    internal fun oppositeOf(button: CanonicalButton): CanonicalButton = when (button) {
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

    private fun axisRange(direction: Int, role: AnalogRole): Triple<Float, Float, Float> {
        // Trigger axes (DIGITAL_BUTTON) are unipolar: rest at 0, full press at +/-1. Mapping
        // them as bipolar would normalize axis-at-rest to 0.5 -- past the 0.5 digital
        // threshold but below the 0 baseline, so a trigger that just sits at rest reads as
        // "barely pressed" forever. Stick axes stay bipolar.
        if (role == AnalogRole.DIGITAL_BUTTON) {
            return if (direction >= 0) Triple(0f, 0f, 1f) else Triple(0f, 0f, -1f)
        }
        return if (direction >= 0) Triple(-1f, 0f, 1f) else Triple(1f, 0f, -1f)
    }

    private fun stableIdFor(
        device: ConnectedDevice,
        entry: RetroArchCfgEntry,
    ): String {
        val base = device.name.ifEmpty { entry.deviceName.ifEmpty { "controller" } }
        val slug = "ra_" + slugify(base)
        // Mappings are scoped per pad model, so no per-unit suffix. Pinned entries append the model
        // to keep two handhelds' built-in profiles distinct in input-db, even though model-aware
        // seeding means only one of them is ever on a given device.
        val model = entry.buildModel?.trim()?.takeIf { it.isNotEmpty() }
        return if (model != null) "${slug}_${slugify(model)}" else slug
    }

    internal fun slugify(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
