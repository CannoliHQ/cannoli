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
     */
    fun resolve(device: ConnectedDevice): DeviceMapping {
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
