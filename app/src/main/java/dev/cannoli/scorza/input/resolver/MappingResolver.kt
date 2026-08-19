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
        val disk = diskRepository.listEntries()
        // Assets cover the window before seeding lands or while storage is unreadable.
        val candidates = disk.ifEmpty { bundledRetroArchEntries.entries() }

        val best = candidates
            .mapNotNull { entry -> IdentityMatcher.rank(entry, device)?.let { entry to it } }
            .minWithOrNull(
                compareBy<Pair<RetroArchCfgEntry, MatchRank>> {
                    if (it.first.isUserOwned) 0 else 1
                }
                    .thenBy { it.second.ordinal }
                    .thenBy { if (IdentityMatcher.builtinAgrees(it.first, device)) 0 else 1 }
                    // A directory listing has no order of its own, so break remaining ties on the
                    // filename rather than letting the filesystem decide a pad's layout.
                    .thenBy { it.first.fileName.orEmpty() }
            )
            ?.first

        return best?.let { RetroArchAutoconfigImporter.import(it, device) }
            ?: AndroidDefaultMappingFactory().create(device)
    }
}
