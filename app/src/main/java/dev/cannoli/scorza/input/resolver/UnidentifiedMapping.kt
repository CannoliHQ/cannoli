package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.MappingSource

fun unidentifiedMapping(device: ConnectedDevice, genericName: String): DeviceMapping {
    val fallbackId = "${device.vendorId}_${device.productId}_${device.name.hashCode()}"
    val nameSlug = device.name.takeIf { it.isNotEmpty() }
        ?.let { RetroArchAutoconfigImporter.slugify(it) }
        ?.takeIf { it.isNotEmpty() }
    return DeviceMapping(
        id = "android_default_" + (nameSlug ?: fallbackId),
        displayName = device.name.ifEmpty { genericName },
        match = DeviceMatchRule(
            name = device.name.takeIf { it.isNotEmpty() },
            vendorId = device.vendorId.takeIf { it != 0 },
            productId = device.productId.takeIf { it != 0 },
            builtin = device.isBuiltIn,
        ),
        bindings = emptyMap(),
        source = MappingSource.UNIDENTIFIED,
        unmodeledLines = captureLines(device),
    )
}

private fun captureLines(device: ConnectedDevice): List<String> = buildList {
    device.androidBuildModel.takeIf { it.isNotEmpty() }
        ?.let { add("submission_build_model = \"${it.replace("\"", "")}\"") }
    add("submission_source_mask = \"${device.sourceMask}\"")
}
