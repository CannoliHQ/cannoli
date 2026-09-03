package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.MappingSource

/**
 * A pad Cannoli has no profile for: real identity, and no bindings at all.
 *
 * It replaced a factory that invented a standard layout. A guessed layout is seldom right, and the
 * only thing it was ever needed for was carrying the user as far as the setup wizard, which reads
 * raw presses and needs no bindings to work. So nothing is guessed here: the mapping says which
 * device this is and that we do not know it, and the wizard fills in the rest from what the user
 * actually presses.
 *
 * The `android_default_` id prefix is kept deliberately. The id becomes the cfg's filename, so
 * changing it would orphan every profile already written by an earlier build.
 */
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
    )
}
