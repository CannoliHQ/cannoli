package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping

class MappingResolver(
    private val diskRepository: AutoconfigRepository,
    private val bundledRetroArchEntries: BundledAutoconfigEntries,
) {

    /**
     * Resolve a connected device to its mapping.
     *
     * [persistenceDescriptor] is the identifier the caller wants the resulting mapping to be
     * persisted under. Callers (the bridge) compute this from sibling-folded InputDevices — the
     * gamepad's own descriptor when it's unique, or a sibling's descriptor on Retroid-style
     * phantom-rewrite hosts where the gamepad endpoint has a degenerate (empty-uniqueId) hash.
     * Null means "use the device's own descriptor (or none)".
     */
    fun resolve(device: ConnectedDevice, persistenceDescriptor: String? = null): DeviceMapping {
        val effectiveDescriptor = persistenceDescriptor?.takeIf { it.isNotEmpty() }
            ?: device.descriptor.takeIf { it.isNotEmpty() }
        val disk = diskRepository.listEntries()

        val user = bestUserEntry(disk.filter { it.cannoliUser }, device, effectiveDescriptor)
        if (user != null) return RetroArchAutoconfigImporter.import(user, device, persistenceDescriptor)

        // A cfg pinned to a specific Build.MODEL is exclusive to that device: it disambiguates
        // shared name+vid/pid identities (e.g. the AYN Thor / Portal / Odin 3 all reporting the
        // same "Odin Controller" 0x2020:0x0111) that no name/vid/pid score can tell apart.
        val buildModelMatch = disk.firstOrNull { matchesBuildModel(it, device) }
        if (buildModelMatch != null) return RetroArchAutoconfigImporter.import(buildModelMatch, device, persistenceDescriptor)

        val diskMatch = bestRetroArchEntry(disk.filterNot { hasBuildModel(it) }, device)
        if (diskMatch != null) return RetroArchAutoconfigImporter.import(diskMatch, device, persistenceDescriptor)

        // Same content as the database, for the window before seeding lands or while storage is
        // unreadable.
        val assetBuildModelMatch = bundledRetroArchEntries.entries().firstOrNull { matchesBuildModel(it, device) }
        if (assetBuildModelMatch != null) return RetroArchAutoconfigImporter.import(assetBuildModelMatch, device, persistenceDescriptor)

        val assetMatch = bestRetroArchEntry(
            bundledRetroArchEntries.entries().filterNot { hasBuildModel(it) },
            device,
        )
        if (assetMatch != null) return RetroArchAutoconfigImporter.import(assetMatch, device, persistenceDescriptor)

        return AndroidDefaultMappingFactory().create(device, persistenceDescriptor)
    }

    // A user file names the exact pad it was written for, so descriptor equality outranks the
    // identity match every same-model pad would also satisfy.
    private fun bestUserEntry(
        candidates: List<RetroArchCfgEntry>,
        device: ConnectedDevice,
        descriptor: String?,
    ): RetroArchCfgEntry? {
        if (candidates.isEmpty()) return null
        descriptor?.let { d -> candidates.firstOrNull { it.descriptor == d }?.let { return it } }
        return bestRetroArchEntry(candidates, device)
    }

    // Name signal beats VID/PID signal when they disagree. On phantom-rewrite hosts (Retroid
    // handhelds rewriting a paired BT pad's gamepad endpoint to report the built-in's VID/PID
    // while keeping the BT pad's own name), the device's reported VID/PID identifies a different
    // bundled cfg than the device's name does, and only the name-matching cfg has the right
    // button layout for the physical pad in the user's hand.
    private fun bestRetroArchEntry(
        entries: List<RetroArchCfgEntry>,
        device: ConnectedDevice,
    ): RetroArchCfgEntry? {
        var nameAndVidPid: RetroArchCfgEntry? = null
        var nameOnly: RetroArchCfgEntry? = null
        var vidPidOnly: RetroArchCfgEntry? = null
        for (entry in entries) {
            val nameMatch = entry.deviceName.isNotEmpty() && entry.deviceName == device.name
            val hasVidPid = device.vendorId != 0 && device.productId != 0 &&
                entry.vendorId != null && entry.productId != null
            val vidPidMatch = hasVidPid &&
                entry.vendorId == device.vendorId &&
                entry.productId == device.productId
            when {
                nameMatch && vidPidMatch -> if (nameAndVidPid == null) nameAndVidPid = entry
                nameMatch -> if (nameOnly == null) nameOnly = entry
                vidPidMatch -> if (vidPidOnly == null) vidPidOnly = entry
            }
        }
        return nameAndVidPid ?: nameOnly ?: vidPidOnly
    }

    private fun hasBuildModel(entry: RetroArchCfgEntry): Boolean = !entry.buildModel.isNullOrBlank()

    private fun matchesBuildModel(entry: RetroArchCfgEntry, device: ConnectedDevice): Boolean {
        val pinned = entry.buildModel?.trim()
        if (pinned.isNullOrEmpty() || !pinned.equals(device.androidBuildModel.trim(), ignoreCase = true)) return false
        // Build.MODEL alone matches every pad on that handheld, including external controllers that
        // report the host's model, so also require the built-in pad's own vid/pid. A DualSense
        // plugged into an AYN Thor must not inherit the Thor's cfg. The shared-identity handhelds
        // (Thor / Portal / Odin 3) all report 8224:273, so they still disambiguate by Build.MODEL.
        return device.vendorId != 0 && device.productId != 0 &&
            entry.vendorId == device.vendorId && entry.productId == device.productId
    }
}
