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
        unmodeledLines = captureLines(device),
    )
}

/**
 * What the device says about itself, recorded for whoever curates this pad into the input database.
 *
 * Written here because this is the only moment the answers exist: the model is the handheld that
 * built the mapping rather than whichever one reads the cfg later, and the source mask is only
 * knowable while the pad is connected. Neither can be recovered from the file afterwards.
 *
 * Deliberately not `cannoli_build_model` and `cannoli_source_mask`. Those are match keys, and
 * writing them here would pin a profile the user has not verified to one handheld model. Under
 * their own prefix they are outside `MANAGED_KEYS`, so they ride in `unmodeledLines` untouched,
 * match nothing, and fail the database's own validator until a human converts them.
 */
private fun captureLines(device: ConnectedDevice): List<String> = buildList {
    // A quote would produce a line the parser cannot match, exactly as RetroArchCfgWriter guards.
    device.androidBuildModel.takeIf { it.isNotEmpty() }
        ?.let { add("submission_build_model = \"${it.replace("\"", "")}\"") }
    add("submission_source_mask = \"${device.sourceMask}\"")
}
